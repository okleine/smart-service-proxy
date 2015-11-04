/**
 * Copyright (c) 2012, all partners of project SPITFIRE (core://www.spitfire-project.eu)
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 *
 *  - Redistributions of source code must retain the above copyright notice, this list of conditions and the following
 *    disclaimer.
 *
 *  - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
 *    following disclaimer in the documentation and/or other materials provided with the distribution.
 *
 *  - Neither the backendName of the University of Luebeck nor the names of its contributors may be used to endorse or promote
 *    products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package eu.spitfire.ssp.server.handler;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import eu.spitfire.ssp.backend.generic.DataOrigin;
import eu.spitfire.ssp.backend.generic.DataOriginMapper;
import eu.spitfire.ssp.server.internal.message.DataOriginDeregistrationRequest;
import eu.spitfire.ssp.server.internal.message.DataOriginRegistrationRequest;
import eu.spitfire.ssp.server.internal.message.DataOriginReplacementRequest;
import eu.spitfire.ssp.server.internal.message.WebserviceRegistration;
import eu.spitfire.ssp.server.webservices.HttpWebservice;
import eu.spitfire.ssp.server.webservices.Styles;
import eu.spitfire.ssp.server.internal.utils.HttpResponseFactory;
import org.jboss.netty.channel.*;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;


/**
 * The {@link HttpRequestDispatcher} is the topmost handler of the netty stack to receive incoming
 * HTTP requests. It contains a mapping from {@link URI} to {@link eu.spitfire.ssp.server.webservices.HttpWebservice} instances to
 * forward the request to the proper processor. *
 *
 * @author Oliver Kleine
 */
public class HttpRequestDispatcher extends SimpleChannelHandler {

    private Logger log = LoggerFactory.getLogger(this.getClass().getName());

	//Maps graph URI prefixes with WebServices to handle incoming HTTP requests
	private Map<URI, HttpWebservice> registeredWebservices;
    private Styles styleWebservice;

    public HttpRequestDispatcher(Styles styleWebservice)
            throws Exception {

        this.registeredWebservices = Collections.synchronizedMap(new TreeMap<>());
        this.styleWebservice = styleWebservice;
    }


    /**
     * This method is invoked by the netty framework for incoming message from remote peers. It forwards the incoming
     * {@link HttpRequest} contained in the {@link MessageEvent} to the proper instance of
     * {@link eu.spitfire.ssp.server.webservices.HttpWebservice}, awaits its result asynchronously and sends the
     * response to the client.
     *
     * @param ctx the {@link ChannelHandlerContext} to link actual task to a {@link Channel}
     * @param me the {@link MessageEvent} containing the {@link HttpRequest}
     * @throws Exception
     */
	@Override
	public void messageReceived(final ChannelHandlerContext ctx, final MessageEvent me) throws Exception {

        if(!(me.getMessage() instanceof HttpRequest)) {
			ctx.sendUpstream(me);
            return;
		}

        me.getFuture().setSuccess();
        final HttpRequest httpRequest = (HttpRequest) me.getMessage();

        //Create resource proxy uri from request
        //String tmp = ;
        URI proxyURI = new URI(URLDecoder.decode(httpRequest.getUri().replace("+", "%2B"), "UTF-8").replace("%2B", "+"));

        log.info("Received HTTP request for proxy Webservice {}", proxyURI.toString());

        HttpWebservice httpWebservice;
        if(proxyURI.getPath().startsWith("/style")){
            httpWebservice = styleWebservice;
        }
        else{
            //Lookup proper http request processor
            httpWebservice = registeredWebservices.get(proxyURI);
        }

        //Send NOT FOUND if there is no proper processor
        if(httpWebservice == null){
            log.warn("No HttpWebservice found for {}. Send error response.", proxyURI);
            HttpResponse httpResponse = HttpResponseFactory.createHttpResponse(httpRequest.getProtocolVersion(),
                    HttpResponseStatus.NOT_FOUND, "404 Not Found: " + proxyURI);
            writeHttpResponse(ctx.getChannel(), httpResponse, (InetSocketAddress) me.getRemoteAddress());
        }

        //Forward request to responsible HTTP Webservice instance
        else{
            if(!(ctx.getChannel().getPipeline().getLast() instanceof HttpWebservice)){
                ctx.getChannel().getPipeline().addLast("HTTP Webservice", httpWebservice);
            }
            ctx.sendUpstream(me);
        }
    }

//    private HttpWebservice getHttpWebservice(String proxyURI)

    @Override
    public void writeRequested(ChannelHandlerContext ctx, MessageEvent me) throws Exception {
        log.debug("Downstream: {}.", me.getMessage());

        if(me.getMessage() instanceof WebserviceRegistration){
            WebserviceRegistration message = (WebserviceRegistration) me.getMessage();

            URI webserviceUri = message.getLocalUri();
            //TODO
//            if(registeredWebservices.containsKey(webserviceUri)){
//                me.getFuture().setFailure(new WebserviceAlreadyRegisteredException(message.getLocalUri()));
//                return;
//            }

            registerProxyWebservice(webserviceUri, message.getHttpWebservice());
            me.getFuture().setSuccess();
            return;
        }

        else if(me.getMessage() instanceof DataOriginRegistrationRequest){
            final DataOriginRegistrationRequest request = (DataOriginRegistrationRequest) me.getMessage();

            URI graphName = request.getDataOrigin().getGraphName();
            Object identifier = request.getDataOrigin().getIdentifier();
            DataOriginMapper proxyWebservice = request.getHttpProxyService();

            log.info("Try to register graph \"{}\" from data origin \"{}\" with backend \"{}\".",
                    new Object[]{graphName, identifier, proxyWebservice.getBackendName()});

            final URI proxyURI = new URI("/?graph=" + graphName);
            registerProxyWebservice(proxyURI, request.getHttpProxyService());

            Futures.addCallback(request.getRegistrationFuture(), new FutureCallback<Object>() {
                @Override
                public void onSuccess(Object result) {
                    //nothing to do
                }

                @Override
                public void onFailure(Throwable t) {
                        unregisterProxyWebservice(proxyURI);
                }

            });
        }

        else if(me.getMessage() instanceof DataOriginDeregistrationRequest){
            DataOriginDeregistrationRequest removalMessage = (DataOriginDeregistrationRequest) me.getMessage();
            URI proxyUri = new URI("/?graph=" + removalMessage.getDataOrigin().getGraphName());

            unregisterProxyWebservice(proxyUri);
        }

        else if(me.getMessage() instanceof DataOriginReplacementRequest){
            DataOriginReplacementRequest request = (DataOriginReplacementRequest) me.getMessage();

            // remove old data origin
            DataOrigin oldDataOrigin = request.getOldDataOrigin();
            URI oldProxyURI = new URI("/?graph=" + oldDataOrigin.getGraphName());
            HttpWebservice proxyService = this.registeredWebservices.get(oldProxyURI);

            unregisterProxyWebservice(oldProxyURI);

            // add new data origin
            DataOrigin newDataOrigin = request.getNewDataOrigin();
            URI newProxyURI = new URI("/?graph=" + newDataOrigin.getGraphName());
            registerProxyWebservice(newProxyURI, proxyService);
        }

        ctx.sendDownstream(me);
    }



    /**
     * Sends an HTTP response to the given remote address
     *
     * @param channel the {@link Channel} to send the response over
     * @param httpResponse the {@link HttpResponse} to be sent
     * @param clientAddress the recipient of the response
     */
    private void writeHttpResponse(Channel channel, final HttpResponse httpResponse,
                                   final InetSocketAddress clientAddress){

        ChannelFuture future = Channels.write(channel, httpResponse, clientAddress);
        future.addListener(ChannelFutureListener.CLOSE);
    }


    private void registerProxyWebservice(URI proxyUri, HttpWebservice httpWebservice){
        if(registeredWebservices.containsKey(proxyUri))
            return;

         registeredWebservices.put(proxyUri, httpWebservice);
         log.info("Registered new Webservice: {}", proxyUri);
    }


    private boolean unregisterProxyWebservice(URI proxyUri){

        if(registeredWebservices.remove(proxyUri) != null){
            log.info("Successfully removed proxy Webservice \"{}\".", proxyUri);
            return true;
        }
        else{
            log.warn("Could not remove proxy Webservice \"{}\" (NOT FOUND)", proxyUri);
            return false;
        }
    }



    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception{

        if(ctx.getChannel().isConnected()){

            Throwable cause = e.getCause();
            log.warn("Exception caught! Send Error Response.", cause);

            HttpResponse httpResponse = HttpResponseFactory.createHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.INTERNAL_SERVER_ERROR, cause.getMessage());

            writeHttpResponse(ctx.getChannel(), httpResponse, (InetSocketAddress) ctx.getChannel().getRemoteAddress());
        }
        else{
            log.warn("Exception on an unconnected channel! IGNORE.", e.getCause());
            ctx.getChannel().close();
        }
    }

    public Map<URI, HttpWebservice> getRegisteredWebservices(){
        return this.registeredWebservices;
    }
}


