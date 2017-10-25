package com.nike.wingtips.apache.httpclient;

import com.nike.wingtips.Span;
import com.nike.wingtips.Span.SpanPurpose;
import com.nike.wingtips.Tracer;
import com.nike.wingtips.apache.httpclient.util.WingtipsApacheHttpClientUtil;

import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpExecutionAware;
import org.apache.http.client.methods.HttpRequestWrapper;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.execchain.ClientExecChain;
import org.apache.http.protocol.HttpProcessor;

import java.io.IOException;

import static com.nike.wingtips.apache.httpclient.util.WingtipsApacheHttpClientUtil.propagateTracingHeaders;

/**
 * (NOTE: This class is recommended instead of {@link WingtipsApacheHttpClientInterceptor} if you have control over
 * which {@link HttpClientBuilder} is used to create your {@link HttpClient}s. Reasons for this are described at the
 * bottom of this class javadoc.)
 *
 * <p>This class is an extension of {@link HttpClientBuilder}, where any {@link HttpClient}s that you {@link #build()}
 * will propagate Wingtips tracing on the {@link HttpClient} request headers and optionally surround the request in
 * a subspan.
 *
 * <p>If the subspan option is enabled but there's no current span on the current thread when a request executes,
 * then a new root span (new trace) will be created rather than a subspan. In either case the newly created span will
 * have a {@link Span#getSpanPurpose()} of {@link Span.SpanPurpose#CLIENT} since the subspan is for a client call.
 * The {@link Span#getSpanName()} for the newly created span will be generated by {@link
 * #getSubspanSpanName(HttpRequest)} - override that method if you want a different span naming format.
 *
 * <p>Note that if you have the subspan option turned off then the {@link Tracer#getCurrentSpan()}'s tracing info
 * will be propagated downstream if it's available, but if no current span exists on the current thread when the
 * request is executed then no tracing logic will occur as there's no tracing info to propagate. Turning on the
 * subspan option mitigates this as it guarantees there will be a span to propagate.
 *
 * <p>As mentioned at the top of this class' javadocs, this class is the preferred way to automatically handle Wingtips
 * tracing propagation and subspans for {@link HttpClient} requests if you have control over the {@link
 * HttpClientBuilder} that gets used to generate {@link HttpClient}s. The other option is interceptors via {@link
 * WingtipsApacheHttpClientInterceptor} (when you don't have control over the {@link HttpClientBuilder}), however this
 * class is preferred instead of the interceptor where possible for the following reasons:
 * <ul>
 *     <li>
 *         There are several ways for interceptors to be accidentally wiped out, e.g. {@link
 *         HttpClientBuilder#setHttpProcessor(HttpProcessor)}.
 *     </li>
 *     <li>
 *         This class makes sure that any subspan *fully* surrounds the request, including all other interceptors that
 *         are executed.
 *     </li>
 *     <li>
 *         When using the interceptor instead of this class you have to remember to add the interceptor as both a
 *         request interceptor ({@link HttpRequestInterceptor}) *and* response interceptor ({@link
 *         HttpResponseInterceptor}) on the {@link HttpClientBuilder} you use, or tracing will be broken.
 *     </li>
 * </ul>
 * That said, the interceptors do work perfectly well when you aren't able to use this builder class as long as they
 * are setup correctly.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class WingtipsHttpClientBuilder extends HttpClientBuilder {

    protected boolean surroundCallsWithSubspan;

    /**
     * Creates a new instance with the subspan option turned on.
     */
    public WingtipsHttpClientBuilder() {
        this(true);
    }

    /**
     * Creates a new instance with the subspan option set to the value of the {@code surroundCallsWithSubspan} argument.
     * 
     * @param surroundCallsWithSubspan Pass in true to have requests surrounded in a subspan, false to disable the
     * subspan option.
     */
    public WingtipsHttpClientBuilder(boolean surroundCallsWithSubspan) {
        this.surroundCallsWithSubspan = surroundCallsWithSubspan;
    }

    /**
     * @return Static factory method for creating a new {@link WingtipsHttpClientBuilder} instance with the subspan
     * option turned on.
     */
    public static WingtipsHttpClientBuilder create() {
        return new WingtipsHttpClientBuilder();
    }

    /**
     * @param surroundCallsWithSubspan Pass in true to have requests surrounded in a subspan, false to disable the
     * subspan option.
     * @return Static factory method for creating a new {@link WingtipsHttpClientBuilder} instance with the subspan
     * option set to the value of the {@code surroundCallsWithSubspan} argument.
     */
    public static WingtipsHttpClientBuilder create(boolean surroundCallsWithSubspan) {
        return new WingtipsHttpClientBuilder(surroundCallsWithSubspan);
    }

    @Override
    protected ClientExecChain decorateProtocolExec(final ClientExecChain protocolExec) {
        final boolean myHttpClientSurroundCallsWithSubspan = surroundCallsWithSubspan;

        return new ClientExecChain() {
            @Override
            @SuppressWarnings("TryFinallyCanBeTryWithResources")
            public CloseableHttpResponse execute(HttpRoute route, HttpRequestWrapper request,
                                                 HttpClientContext clientContext,
                                                 HttpExecutionAware execAware) throws IOException, HttpException {

                Tracer tracer = Tracer.getInstance();
                Span spanAroundCall = null;
                if (myHttpClientSurroundCallsWithSubspan) {
                    // Will start a new trace if necessary, or a subspan if a trace is already in progress.
                    spanAroundCall = tracer.startSpanInCurrentContext(getSubspanSpanName(request), SpanPurpose.CLIENT);
                }

                try {
                    propagateTracingHeaders(request, tracer.getCurrentSpan());
                    return protocolExec.execute(route, request, clientContext, execAware);
                }
                finally {
                    if (spanAroundCall != null) {
                        // Span.close() contains the logic we want - if the spanAroundCall was an overall span (new
                        //      trace) then tracer.completeRequestSpan() will be called, otherwise it's a subspan and
                        //      tracer.completeSubSpan() will be called.
                        spanAroundCall.close();
                    }
                }
            }
        };
    }

    /**
     * Returns the name that should be used for the subspan surrounding the call. Defaults to {@code
     * apachehttpclient_downstream_call-[HTTP_METHOD]_[REQUEST_URI]} with any query string stripped, e.g. for a GET
     * call to https://foo.bar/baz?stuff=things, this would return {@code
     * "apachehttpclient_downstream_call-GET_https://foo.bar/baz"}. You can override this
     * method to return something else if you want a different subspan name format.
     *
     * @param request The request that is about to be executed.
     * @return The name that should be used for the subspan surrounding the call.
     */
    protected String getSubspanSpanName(HttpRequest request) {
        return WingtipsApacheHttpClientUtil.getSubspanSpanName(request);
    }

    /**
     * @return The current value of the subspan option.
     */
    public boolean isSurroundCallsWithSubspan() {
        return surroundCallsWithSubspan;
    }

    /**
     * Sets the builder's subspan option value. New {@link HttpClient}s generated with {@link #build()} will use this
     * value when processing requests, but setting this here will not affect any {@link HttpClient}s that have already
     * been built - it only affects future-generated {@link HttpClient}s
     *
     * @param surroundCallsWithSubspan Pass in true to have requests surrounded in a subspan, false to disable the
     * subspan option.
     * @return This builder after setting the subspan option to the desired value.
     */
    public WingtipsHttpClientBuilder setSurroundCallsWithSubspan(boolean surroundCallsWithSubspan) {
        this.surroundCallsWithSubspan = surroundCallsWithSubspan;
        return this;
    }
}
