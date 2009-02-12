//========================================================================
//$Id: HttpConnection.java,v 1.13 2005/11/25 21:01:45 gregwilkins Exp $
//Copyright 2004-2005 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//Licensed under the Apache License, Version 2.0 (the "License");
//you may not use this file except in compliance with the License.
//You may obtain a copy of the License at
//http://www.apache.org/licenses/LICENSE-2.0
//Unless required by applicable law or agreed to in writing, software
//distributed under the License is distributed on an "AS IS" BASIS,
//WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//See the License for the specific language governing permissions and
//limitations under the License.
//========================================================================

package org.mortbay.jetty;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;

import javax.servlet.DispatcherType;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;

import org.mortbay.jetty.http.AbstractGenerator;
import org.mortbay.jetty.http.EncodedHttpURI;
import org.mortbay.jetty.http.Generator;
import org.mortbay.jetty.http.HttpContent;
import org.mortbay.jetty.http.HttpFields;
import org.mortbay.jetty.http.HttpGenerator;
import org.mortbay.jetty.http.HttpHeaderValues;
import org.mortbay.jetty.http.HttpHeaders;
import org.mortbay.jetty.http.HttpMethods;
import org.mortbay.jetty.http.HttpParser;
import org.mortbay.jetty.http.HttpStatus;
import org.mortbay.jetty.http.HttpURI;
import org.mortbay.jetty.http.HttpVersions;
import org.mortbay.jetty.http.MimeTypes;
import org.mortbay.jetty.http.Parser;
import org.mortbay.jetty.io.Buffer;
import org.mortbay.jetty.io.Connection;
import org.mortbay.jetty.io.EndPoint;
import org.mortbay.jetty.io.EofException;
import org.mortbay.jetty.io.HttpException;
import org.mortbay.jetty.io.BufferCache.CachedBuffer;
import org.mortbay.jetty.io.nio.SelectChannelEndPoint;
import org.mortbay.jetty.util.QuotedStringTokenizer;
import org.mortbay.jetty.util.StringUtil;
import org.mortbay.jetty.util.URIUtil;
import org.mortbay.jetty.util.log.Log;
import org.mortbay.jetty.util.resource.Resource;
import org.mortbay.jetty.util.thread.Timeout;

/**
 * <p>A HttpConnection represents the connection of a HTTP client to the server
 * and is created by an instance of a {@link Connector}. It's prime function is 
 * to associate {@link Request} and {@link Response} instances with a {@link EndPoint}.
 * </p>
 * <p>
 * A connection is also the prime mechanism used by jetty to recycle objects without
 * pooling.  The {@link Request},  {@link Response}, {@link HttpParser}, {@link HttpGenerator}
 * and {@link HttpFields} instances are all recycled for the duraction of
 * a connection. Where appropriate, allocated buffers are also kept associated
 * with the connection via the parser and/or generator.
 * </p>
 * 
 * 
 * @author gregw
 * 
 */
public class HttpConnection implements Connection
{
    private static int UNKNOWN = -2;
    private static ThreadLocal<HttpConnection> __currentConnection = new ThreadLocal<HttpConnection>();

    private final long _timeStamp=System.currentTimeMillis();
    private int _requests;
    private volatile boolean _handling;
    
    protected final Connector _connector;
    protected final EndPoint _endp;
    protected final Server _server;
    protected final HttpURI _uri;

    protected final Parser _parser;
    protected final HttpFields _requestFields;
    protected final Request _request;
    protected ServletInputStream _in;

    protected final Generator _generator;
    protected final HttpFields _responseFields;
    protected final Response _response;
    protected Output _out;
    protected OutputWriter _writer;
    protected PrintWriter _printWriter;

    int _include;
    
    private Object _associatedObject; // associated object
    
    private transient int _expect = UNKNOWN;
    private transient int _version = UNKNOWN;
    private transient boolean _head = false;
    private transient boolean _host = false;
    private transient boolean  _delayedHandling=false;

    /* ------------------------------------------------------------ */
    public static HttpConnection getCurrentConnection()
    {
        return (HttpConnection) __currentConnection.get();
    }
    
    /* ------------------------------------------------------------ */
    protected static void setCurrentConnection(HttpConnection connection)
    {
        __currentConnection.set(connection);
    }

    /* ------------------------------------------------------------ */
    /** Constructor
     * 
     */
    public HttpConnection(Connector connector, EndPoint endpoint, Server server)
    {
        _uri = URIUtil.__CHARSET==StringUtil.__UTF8?new HttpURI():new EncodedHttpURI(URIUtil.__CHARSET);
        _connector = connector;
        _endp = endpoint;
        _parser = new HttpParser(_connector, endpoint, new RequestHandler(), _connector.getHeaderBufferSize(), _connector.getRequestBufferSize());
        _requestFields = new HttpFields();
        _responseFields = new HttpFields();
        _request = new Request(this);
        _response = new Response(this);
        _generator = new HttpGenerator(_connector, _endp, _connector.getHeaderBufferSize(), _connector.getResponseBufferSize());
        _generator.setSendServerVersion(server.getSendServerVersion());
        _server = server;
    }
    
    protected HttpConnection(Connector connector, EndPoint endpoint, Server server,
            Parser parser, Generator generator, Request request)
    {
        _uri = URIUtil.__CHARSET==StringUtil.__UTF8?new HttpURI():new EncodedHttpURI(URIUtil.__CHARSET);
        _connector = connector;
        _endp = endpoint;
        _parser = parser;
        _requestFields = new HttpFields();
        _responseFields = new HttpFields();
        _request = request;
        _response = new Response(this);
        _generator = generator;
        _generator.setSendServerVersion(server.getSendServerVersion());
        _server = server;
    }

    /* ------------------------------------------------------------ */
    public void destroy()
    {
        synchronized(this)
        {
            while(_handling)
                Thread.yield();

            if (_parser!=null)
                _parser.reset(true);

            if (_generator!=null)
                _generator.reset(true);

            if (_requestFields!=null)
                _requestFields.destroy();

            if (_responseFields!=null)
                _responseFields.destroy();
        }
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return the parser used by this connection
     */        
    public Parser getParser()
    {
        return _parser;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return the number of requests handled by this connection
     */
    public int getRequests()
    {
        return _requests;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The time this connection was established.
     */
    public long getTimeStamp()
    {
        return _timeStamp;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return Returns the associatedObject.
     */
    public Object getAssociatedObject()
    {
        return _associatedObject;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param associatedObject The associatedObject to set.
     */
    public void setAssociatedObject(Object associatedObject)
    {
        _associatedObject = associatedObject;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the connector.
     */
    public Connector getConnector()
    {
        return _connector;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the requestFields.
     */
    public HttpFields getRequestFields()
    {
        return _requestFields;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the responseFields.
     */
    public HttpFields getResponseFields()
    {
        return _responseFields;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The result of calling {@link #getConnector}.{@link Connector#isConfidential(Request) isCondidential}(request), or false
     *  if there is no connector.
     */
    public boolean isConfidential(Request request)
    {
        if (_connector!=null)
            return _connector.isConfidential(request);
        return false;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Find out if the request is INTEGRAL security.
     * @param request
     * @return <code>true</code> if there is a {@link #getConnector() connector} and it considers <code>request</code>
     *         to be {@link Connector#isIntegral(Request) integral}
     */
    public boolean isIntegral(Request request)
    {
        if (_connector!=null)
            return _connector.isIntegral(request);
        return false;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The {@link EndPoint} for this connection.
     */
    public EndPoint getEndPoint()
    {
        return _endp;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return <code>false</code> (this method is not yet implemented)
     */
    public boolean getResolveNames()
    {
        return _connector.getResolveNames();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the request.
     */
    public Request getRequest()
    {
        return _request;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the response.
     */
    public Response getResponse()
    {
        return _response;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The input stream for this connection. The stream will be created if it does not already exist.
     */
    public ServletInputStream getInputStream() throws IOException
    {
        // If the client is expecting 100 CONTINUE, then send it now.
        if (_expect == HttpHeaderValues.CONTINUE_ORDINAL)
        {
            if (((HttpParser)_parser).getHeaderBuffer()==null || ((HttpParser)_parser).getHeaderBuffer().length()<2)
            {
                _generator.setResponse(HttpStatus.ORDINAL_100_Continue, null);
                _generator.completeHeader(null, true);
                _generator.complete();
                _generator.reset(false);
            }
            _expect = UNKNOWN;
        }

        if (_in == null) 
            _in = new HttpParser.Input(((HttpParser)_parser),_connector.getMaxIdleTime());
        return _in;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The output stream for this connection. The stream will be created if it does not already exist.
     */
    public ServletOutputStream getOutputStream()
    {
        if (_out == null) 
            _out = new Output();
        return _out;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return A {@link PrintWriter} wrapping the {@link #getOutputStream output stream}. The writer is created if it
     *    does not already exist.
     */
    public PrintWriter getPrintWriter(String encoding)
    {
        getOutputStream();
        if (_writer==null)
        {
            _writer=new OutputWriter();
            _printWriter=new PrintWriter(_writer)
            {
                /* ------------------------------------------------------------ */
                /* 
                 * @see java.io.PrintWriter#close()
                 */
                public void close() 
                {
                    try
                    {
                        out.close();
                    }
                    catch(IOException e)
                    {
                        Log.debug(e);
                        setError();
                    }
                }
                
            };
        }
        _writer.setCharacterEncoding(encoding);
        return _printWriter;
    }
    
    /* ------------------------------------------------------------ */
    public boolean isResponseCommitted()
    {
        return _generator.isCommitted();
    }

    /* ------------------------------------------------------------ */
    public void handle() throws IOException
    {
        // Loop while more in buffer
        boolean more_in_buffer =true; // assume true until proven otherwise
        boolean progress=true;

        try
        {
            _handling=true;
            setCurrentConnection(this);

            while (more_in_buffer)
            {
                try
                {
                    if (_request._async.isAsync())
                    {
                        Log.debug("resume request",_request);
                        if (!_request._async.isComplete())
                            handleRequest();
                        else if (!_parser.isComplete()) 
                            progress|=_parser.parseAvailable()>0;
                        
                        if (_generator.isCommitted() && !_generator.isComplete())
                            _generator.flush();
                        if (_endp.isBufferingOutput())
                            _endp.flush();
                    }
                    else
                    {
                        // If we are not ended then parse available
                        if (!_parser.isComplete()) 
                            progress|=_parser.parseAvailable()>0;

                        // Do we have more generating to do?
                        // Loop here because some writes may take multiple steps and
                        // we need to flush them all before potentially blocking in the
                        // next loop.
                        while (_generator.isCommitted() && !_generator.isComplete())
                        {
                            long written=_generator.flush();
                            if (written<=0)
                                break;
                            progress=true;
                            if (_endp.isBufferingOutput())
                                _endp.flush();
                        }

                        // Flush buffers
                        if (_endp.isBufferingOutput())
                        {
                            _endp.flush();
                            if (!_endp.isBufferingOutput())
                                progress=true;
                        }

                        if (!progress) 
                            return;
                        progress=false;
                    }
                }
                catch (HttpException e)
                {
                    if (Log.isDebugEnabled())
                    {
                        Log.debug("uri="+_uri);
                        Log.debug("fields="+_requestFields);
                        Log.debug(e);
                    }
                    _generator.sendError(e.getStatus(), e.getReason(), null, true);

                    _parser.reset(true);
                    _endp.close();
                    throw e;
                }
                finally
                {
                    more_in_buffer = _parser.isMoreInBuffer() || _endp.isBufferingInput();  

                    if (_parser.isComplete() && _generator.isComplete() && !_endp.isBufferingOutput())
                    {  
                        if (!_generator.isPersistent())
                        {
                            _parser.reset(true);
                            more_in_buffer=false;
                        }

                        reset(!more_in_buffer);
                        progress=true;
                    }

                    if (_request.isAsyncStarted())
                    {
                        Log.debug("return with suspended request");
                        more_in_buffer=false;
                    }
                    else if (_generator.isCommitted() && !_generator.isComplete() && _endp instanceof SelectChannelEndPoint) // TODO remove SelectChannel dependency
                        ((SelectChannelEndPoint)_endp).setWritable(false);
                }
            }
        }
        finally
        {
            setCurrentConnection(null);
            _handling=false;
        }
    }
    
    /* ------------------------------------------------------------ */
    public void scheduleTimeout(Timeout.Task task, long timeoutMs)
    {
        throw new UnsupportedOperationException();
    }
    
    /* ------------------------------------------------------------ */
    public void cancelTimeout(Timeout.Task task)
    {
        throw new UnsupportedOperationException();
    }
    
    /* ------------------------------------------------------------ */
    public void reset(boolean returnBuffers)
    {
        _parser.reset(returnBuffers); // TODO maybe only release when low on resources
        _requestFields.clear();
        _request.recycle();
        
        _generator.reset(returnBuffers); // TODO maybe only release when low on resources
        _responseFields.clear();
        _response.recycle();
        
        _uri.clear(); 
    }
    
    /* ------------------------------------------------------------ */
    protected void handleRequest() throws IOException
    {
        boolean handling=_server.isRunning() && _request._async.handling();
        boolean error = false;

        String threadName=null;
        try
        {
            if (Log.isDebugEnabled())
            {
                threadName=Thread.currentThread().getName();
                Thread.currentThread().setName(threadName+" - "+_uri);
            }
            
            while (handling)
            {
                _request.setHandled(false);
                try
                {
                    String info=URIUtil.canonicalPath(_uri.getDecodedPath());
                    if (info==null)
                        throw new HttpException(400);
                    _request.setPathInfo(info);

                    if (_out!=null)
                        _out.reopen();

                    if (_request._async.isInitial())
                    {
                        _request.setDispatcherType(DispatcherType.REQUEST);
                        _connector.customize(_endp, _request);
                        _server.handle(this);
                    }
                    else 
                    {
                        _request.setDispatcherType(DispatcherType.ASYNC);
                        _server.handleAsync(this);
                    }

                }
                catch (RetryRequest r)
                {
                    Log.ignore(r);
                }
                catch (EofException e)
                {
                    Log.ignore(e);
                    error=true;
                }
                catch (HttpException e)
                {
                    Log.debug(e);
                    _request.setHandled(true);
                    _response.sendError(e.getStatus(), e.getReason());
                    error=true;
                }
                catch (Exception e)
                {
                    Log.warn(e);
                    _request.setHandled(true);
                    _generator.sendError(500, null, null, true);
                    error=true;
                }
                catch (Error e)
                {
                    Log.warn(e);
                    _request.setHandled(true);
                    _generator.sendError(500, null, null, true);
                    error=true;
                }
                finally
                {   
                    handling = !_request._async.unhandle() && _server != null;
                }
            }
        }
        finally
        {
            if (threadName!=null)
                Thread.currentThread().setName(threadName);

            if (_request._async.isUncompleted())
            {   
                _request._async.doComplete();
                
                if (_expect == HttpHeaderValues.CONTINUE_ORDINAL)
                {
                    // Continue not sent so don't parse any content 
                    _expect = UNKNOWN;
                    if (_parser instanceof HttpParser)
                        ((HttpParser)_parser).setState(HttpParser.STATE_END);
                }

                if(_endp.isOpen())
                {
                    if (_generator.isPersistent())
                        _connector.persist(_endp);

                    if (error) 
                        _endp.close();
                    else
                    {
                        if (!_response.isCommitted() && !_request.isHandled())
                            _response.sendError(HttpServletResponse.SC_NOT_FOUND);
                        _response.complete();
                    }
                }
                else
                {
                    _response.complete(); 
                }

                _request.setHandled(true);
            } 
        }
    }

    /* ------------------------------------------------------------ */
    public void commitResponse(boolean last) throws IOException
    {
        if (!_generator.isCommitted())
        {
            _generator.setResponse(_response.getStatus(), _response.getReason());
            _generator.completeHeader(_responseFields, last);
        }
        if (last) 
            _generator.complete();
    }

    /* ------------------------------------------------------------ */
    public void completeResponse() throws IOException
    {
        if (!_generator.isCommitted())
        {
            _generator.setResponse(_response.getStatus(), _response.getReason());
            _generator.completeHeader(_responseFields, HttpGenerator.LAST);
        }

        _generator.complete();
    }

    /* ------------------------------------------------------------ */
    public void flushResponse() throws IOException
    {
        try
        {
            commitResponse(HttpGenerator.MORE);
            _generator.flush();
        }
        catch(IOException e)
        {
            throw (e instanceof EofException) ? e:new EofException(e);
        }
    }

    /* ------------------------------------------------------------ */
    public Generator getGenerator()
    {
        return _generator;
    }
    

    /* ------------------------------------------------------------ */
    public boolean isIncluding()
    {
        return _include>0;
    }

    /* ------------------------------------------------------------ */
    public void include()
    {
        _include++;
    }

    /* ------------------------------------------------------------ */
    public void included()
    {
        _include--;
        if (_out!=null)
            _out.reopen();
    }

    /* ------------------------------------------------------------ */
    public boolean isIdle()
    {
        return _generator.isIdle() && (_parser.isIdle() || _delayedHandling);
    }
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private class RequestHandler extends HttpParser.EventHandler
    {
        private String _charset;
        
        /*
         * 
         * @see org.mortbay.jetty.HttpParser.EventHandler#startRequest(org.mortbay.io.Buffer,
         *      org.mortbay.io.Buffer, org.mortbay.io.Buffer)
         */
        public void startRequest(Buffer method, Buffer uri, Buffer version) throws IOException
        {
            _host = false;
            _expect = UNKNOWN;
            _delayedHandling=false;
            _charset=null;

            if(_request.getTimeStamp()==0)
                _request.setTimeStamp(System.currentTimeMillis());
            _request.setMethod(method.toString());

            try
            {
                _uri.parse(uri.array(), uri.getIndex(), uri.length());
                _request.setUri(_uri);

                if (version==null)
                {
                    _request.setProtocol(HttpVersions.HTTP_0_9);
                    _version=HttpVersions.HTTP_0_9_ORDINAL;
                }
                else
                {
                    version= HttpVersions.CACHE.get(version);
                    _version = HttpVersions.CACHE.getOrdinal(version);
                    if (_version <= 0) _version = HttpVersions.HTTP_1_0_ORDINAL;
                    _request.setProtocol(version.toString());
                }

                _head = method == HttpMethods.HEAD_BUFFER; // depends on method being decached.
            }
            catch (Exception e)
            {
                throw new HttpException(HttpStatus.ORDINAL_400_Bad_Request,null,e);
            }
        }

        /*
         * @see org.mortbay.jetty.HttpParser.EventHandler#parsedHeaderValue(org.mortbay.io.Buffer)
         */
        public void parsedHeader(Buffer name, Buffer value)
        {
            int ho = HttpHeaders.CACHE.getOrdinal(name);
            switch (ho)
            {
                case HttpHeaders.HOST_ORDINAL:
                    // TODO check if host matched a host in the URI.
                    _host = true;
                    break;
                    
                case HttpHeaders.EXPECT_ORDINAL:
                    value = HttpHeaderValues.CACHE.lookup(value);
                    _expect = HttpHeaderValues.CACHE.getOrdinal(value);
                    break;
                    
                case HttpHeaders.ACCEPT_ENCODING_ORDINAL:
                case HttpHeaders.USER_AGENT_ORDINAL:
                    value = HttpHeaderValues.CACHE.lookup(value);
                    break;
                    
                case HttpHeaders.CONTENT_TYPE_ORDINAL:
                    value = MimeTypes.CACHE.lookup(value);
                    _charset=MimeTypes.getCharsetFromContentType(value);
                    break;

                case HttpHeaders.CONNECTION_ORDINAL:
                    //looks rather clumsy, but the idea is to optimize for a single valued header
                    int ordinal = HttpHeaderValues.CACHE.getOrdinal(value);
                    switch(ordinal)
                    {
                        case -1:
                        { 
                            String[] values = value.toString().split(",");
                            for  (int i=0;values!=null && i<values.length;i++)
                            {
                                CachedBuffer cb = HttpHeaderValues.CACHE.get(values[i].trim());

                                if (cb!=null)
                                {
                                    switch(cb.getOrdinal())
                                    {
                                        case HttpHeaderValues.CLOSE_ORDINAL:
                                            _responseFields.add(HttpHeaders.CONNECTION_BUFFER,HttpHeaderValues.CLOSE_BUFFER);
                                            _generator.setPersistent(false);
                                            break;

                                        case HttpHeaderValues.KEEP_ALIVE_ORDINAL:
                                            if (_version==HttpVersions.HTTP_1_0_ORDINAL)
                                                _responseFields.add(HttpHeaders.CONNECTION_BUFFER,HttpHeaderValues.KEEP_ALIVE_BUFFER);
                                            break;
                                    }
                                }
                            }
                            break;
                        }
                        case HttpHeaderValues.CLOSE_ORDINAL:
                            _responseFields.put(HttpHeaders.CONNECTION_BUFFER,HttpHeaderValues.CLOSE_BUFFER);
                            _generator.setPersistent(false);
                            break;

                        case HttpHeaderValues.KEEP_ALIVE_ORDINAL:
                            if (_version==HttpVersions.HTTP_1_0_ORDINAL)
                                _responseFields.put(HttpHeaders.CONNECTION_BUFFER,HttpHeaderValues.KEEP_ALIVE_BUFFER);
                            break;
                    } 
            }

            _requestFields.add(name, value);
        }

        /*
         * @see org.mortbay.jetty.HttpParser.EventHandler#headerComplete()
         */
        public void headerComplete() throws IOException
        {
            _requests++;
            _generator.setVersion(_version);
            switch (_version)
            {
                case HttpVersions.HTTP_0_9_ORDINAL:
                    break;
                case HttpVersions.HTTP_1_0_ORDINAL:
                    _generator.setHead(_head);
                    break;
                case HttpVersions.HTTP_1_1_ORDINAL:
                    _generator.setHead(_head);
                    
                    if (_server.getSendDateHeader())
                        _responseFields.put(HttpHeaders.DATE_BUFFER, _request.getTimeStampBuffer(),_request.getTimeStamp());
                    
                    if (!_host)
                    {
                        _generator.setResponse(HttpStatus.ORDINAL_400_Bad_Request, null);
                        _responseFields.put(HttpHeaders.CONNECTION_BUFFER, HttpHeaderValues.CLOSE_BUFFER);
                        _generator.completeHeader(_responseFields, true);
                        _generator.complete();
                        return;
                    }

                    if (_expect != UNKNOWN)
                    {
                        if (_expect == HttpHeaderValues.CONTINUE_ORDINAL)
                        {
                        }
                        else if (_expect == HttpHeaderValues.PROCESSING_ORDINAL)
                        {
                        }
                        else
                        {
                            _generator.sendError(HttpStatus.ORDINAL_417_Expectation_Failed, null, null, true);
                            return;
                        }
                    }
                    
                    break;
                default:
            }

            if(_charset!=null)
                _request.setCharacterEncodingUnchecked(_charset);
            
            // Either handle now or wait for first content
            if ((((HttpParser)_parser).getContentLength()<=0 && !((HttpParser)_parser).isChunking())||_expect==HttpHeaderValues.CONTINUE_ORDINAL) 
                handleRequest();
            else
                _delayedHandling=true;
        }

        /* ------------------------------------------------------------ */
        /*
         * @see org.mortbay.jetty.HttpParser.EventHandler#content(int, org.mortbay.io.Buffer)
         */
        public void content(Buffer ref) throws IOException
        {
            if (_delayedHandling)
            {
                _delayedHandling=false;
                handleRequest();
            }
        }

        /*
         * (non-Javadoc)
         * 
         * @see org.mortbay.jetty.HttpParser.EventHandler#messageComplete(int)
         */
        public void messageComplete(long contentLength) throws IOException
        {
            if (_delayedHandling)
            {
                _delayedHandling=false;
                handleRequest();
            }
        }

        /*
         * (non-Javadoc)
         * 
         * @see org.mortbay.jetty.HttpParser.EventHandler#startResponse(org.mortbay.io.Buffer, int,
         *      org.mortbay.io.Buffer)
         */
        public void startResponse(Buffer version, int status, Buffer reason)
        {
            Log.debug("Bad request!: "+version+" "+status+" "+reason);
        }

    }

    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public class Output extends AbstractGenerator.Output 
    {
        Output()
        {
            super((AbstractGenerator)HttpConnection.this._generator,_connector.getMaxIdleTime());
        }
        
        /* ------------------------------------------------------------ */
        /*
         * @see java.io.OutputStream#close()
         */
        public void close() throws IOException
        {
            if (_closed)
                return;
            
            if (!isIncluding() && !_generator.isCommitted())
                commitResponse(HttpGenerator.LAST);
            else
                flushResponse();
            
            super.close();
        }

        
        /* ------------------------------------------------------------ */
        /*
         * @see java.io.OutputStream#flush()
         */
        public void flush() throws IOException
        {
            if (!_generator.isCommitted())
                commitResponse(HttpGenerator.MORE);
            super.flush();
        }

        /* ------------------------------------------------------------ */
        /* 
         * @see javax.servlet.ServletOutputStream#print(java.lang.String)
         */
        public void print(String s) throws IOException
        {
            if (_closed)
                throw new IOException("Closed");
            PrintWriter writer=getPrintWriter(null);
            writer.print(s);
        }

        /* ------------------------------------------------------------ */
        public void sendResponse(Buffer response) throws IOException
        {
            ((HttpGenerator)_generator).sendResponse(response);
        }
        
        /* ------------------------------------------------------------ */
        public void sendContent(Object content) throws IOException
        {
            Resource resource=null;
            
            if (_closed)
                throw new IOException("Closed");
            
            if (_generator.getContentWritten() > 0) throw new IllegalStateException("!empty");

            if (content instanceof HttpContent)
            {
                HttpContent c = (HttpContent) content;
                Buffer contentType = c.getContentType();
                if (contentType != null && !_responseFields.containsKey(HttpHeaders.CONTENT_TYPE_BUFFER))
                {
                    String enc = _response.getSetCharacterEncoding();
                    if(enc==null)
                        _responseFields.add(HttpHeaders.CONTENT_TYPE_BUFFER, contentType);
                    else
                    {
                        if(contentType instanceof CachedBuffer)
                        {
                            CachedBuffer content_type = ((CachedBuffer)contentType).getAssociate(enc);
                            if(content_type!=null)
                                _responseFields.put(HttpHeaders.CONTENT_TYPE_BUFFER, content_type);
                            else
                            {
                                _responseFields.put(HttpHeaders.CONTENT_TYPE_BUFFER, 
                                        contentType+";charset="+QuotedStringTokenizer.quote(enc,";= "));
                            }
                        }
                        else
                        {
                            _responseFields.put(HttpHeaders.CONTENT_TYPE_BUFFER, 
                                    contentType+";charset="+QuotedStringTokenizer.quote(enc,";= "));
                        }
                    }
                }
                if (c.getContentLength() > 0) 
                    _responseFields.putLongField(HttpHeaders.CONTENT_LENGTH_BUFFER, c.getContentLength());
                Buffer lm = c.getLastModified();
                long lml=c.getResource().lastModified();
                if (lm != null) 
                    _responseFields.put(HttpHeaders.LAST_MODIFIED_BUFFER, lm,lml);
                else if (c.getResource()!=null)
                {
                    if (lml!=-1)
                        _responseFields.putDateField(HttpHeaders.LAST_MODIFIED_BUFFER, lml);
                }
                    
                content = c.getBuffer();
                if (content==null)
                    content=c.getInputStream();
            }
            else if (content instanceof Resource)
            {
                resource=(Resource)content;
                _responseFields.putDateField(HttpHeaders.LAST_MODIFIED_BUFFER, resource.lastModified());
                content=resource.getInputStream();
            }
            
            
            if (content instanceof Buffer)
            {
                _generator.addContent((Buffer) content, HttpGenerator.LAST);
                commitResponse(HttpGenerator.LAST);
            }
            else if (content instanceof InputStream)
            {
                InputStream in = (InputStream)content;
                
                try
                {
                    int max = _generator.prepareUncheckedAddContent();
                    Buffer buffer = _generator.getUncheckedBuffer();

                    int len=buffer.readFrom(in,max);

                    while (len>=0)
                    {
                        _generator.completeUncheckedAddContent();
                        _out.flush();

                        max = _generator.prepareUncheckedAddContent();
                        buffer = _generator.getUncheckedBuffer();
                        len=buffer.readFrom(in,max);
                    }
                    _generator.completeUncheckedAddContent();
                    _out.flush();   
                }
                finally
                {
                    if (resource!=null)
                        resource.release();
                    else
                        in.close();
                      
                }
            }
            else
                throw new IllegalArgumentException("unknown content type?");
            
            
        }     
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public class OutputWriter extends AbstractGenerator.OutputWriter
    {
        OutputWriter()
        {
            super(HttpConnection.this._out);
        }
    }

}
