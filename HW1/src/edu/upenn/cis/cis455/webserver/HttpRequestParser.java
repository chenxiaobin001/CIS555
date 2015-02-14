package edu.upenn.cis.cis455.webserver;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import javax.servlet.http.HttpServlet;

import edu.upenn.cis.cis455.webservletinterface.ServletContainer;


public class HttpRequestParser {
	
	private String protocol;
	private String reqUrl;
	private String method;
	private HashMap<String, List<String>> headers;
	private ServletContainer servletContainer;
//	private String body;				do not deal with body
	private CODE code;			
		
	public HashMap<String, List<String>> getHeaders() {
		return this.headers;
	}

	public String getMethod() {
		return this.method;
	}
	
	public String getProtocol() {
		return this.protocol;
	}
	
	public String getUrl() {
		return this.reqUrl;
	}
	
	public HttpRequestParser(ServletContainer servletContainer) {
		code = CODE.NORMAL;
		headers = new HashMap<String, List<String>>();
		this.servletContainer = servletContainer;
	}
	
	public CODE getCode() {
		return code;
	}

	public enum CODE {
		BADREQ, BADDIR, SHUTDOWN, CONTROL, NOFOUND, HEAD, NOIMPLEMENT,
		LISTDIR, FILE, NORMAL, NOALLOW, BADHEADER1, BADHEADER2, SERVLET		//BADHEADER1 unknown	BADHEADER2 http1.1 not host
	}
	
	public void parseHttpRequest(Socket socket) throws IOException{
		
		BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));		
		//parse the initial line
		
		String line = in.readLine();
//		System.out.println(line);
		parseInitialLine(line);			//need to be extend to handle multi-line headers
		//get the headers
		parseHeaders(in);
		
		filterRequest();	
	}
	
	public String matchUrlPattern(String reqUrl) {
		return servletContainer.matchUrlPattern(reqUrl);
	}
	
	
	public HttpServlet getServletFromURL() {
		String urlPattern = matchUrlPattern(reqUrl);
		if (urlPattern == null)	return null;
		String name = servletContainer.getUrlPattern(urlPattern);
		return servletContainer.getServlet(name);
	}
	
	private void parseHeaders(BufferedReader in) throws IOException {
		String line = null;
		int size = 0;
		String lastHeader = null;
		while ((line = in.readLine()) != null) {
			if (line.length() == 0){
				break;
			}
			line = line.trim();
			if (line.contains(":")) {				//the header line
				int idx = line.indexOf(":");
				String header = line.substring(0, idx).toLowerCase(Locale.ENGLISH);
				String value = line.substring(idx + 1).trim();
				if (headers.containsKey(header)) {
					headers.get(header).add(value);
				} else {
					List<String> values = new ArrayList<String>();
					values.add(value);
					headers.put(header, values);
				}
				lastHeader = header;
				size++;
			} else {
				if (size > 0) {
					List<String> values = headers.get(lastHeader);
					int tail = values.size() - 1;
					values.set(tail, values.get(tail) + " " + line);
//					System.out.println(headers.get(size - 1));
				}else {
					this.code = CODE.BADHEADER1;
					return;
				}
			}
		}
		// check http/1.1 host header requirement
		if ("HTTP/1.1".equalsIgnoreCase(this.protocol)) {
			//already in lowercase
			if (!headers.containsKey("host")){
				this.code = CODE.BADHEADER2;
				return;
			}
		}
	}

	// filter out invalid request
	private void filterRequest(){
		if (this.code == CODE.BADREQ || this.code == CODE.BADHEADER1 || this.code == CODE.BADHEADER2){
			return;
		}
		if (!HttpServer.acceptMethods.contains(this.method.toUpperCase(Locale.ENGLISH))) {
			this.code = CODE.NOALLOW;
			return;
		}
		// not GET or HEAD request
		if (!"GET".equalsIgnoreCase(this.method) && !"HEAD".equalsIgnoreCase(this.method)) {
			this.code = CODE.NOIMPLEMENT;
			return;
		}
		String tmpUrl = this.reqUrl.toUpperCase(Locale.ENGLISH);
		if (tmpUrl.contains("HTTP://")){
			this.code = CODE.BADDIR;
			return;
		}
		// security check
		this.reqUrl = parseURL(this.reqUrl);		
		if (reqUrl == null){
			this.code = CODE.BADDIR;
			return;
		}
		// shutdown url
		if ("/shutdown".equalsIgnoreCase(this.reqUrl)){
			this.code = CODE.SHUTDOWN;
			return;
		}
		// control url
		if ("/control".equalsIgnoreCase(this.reqUrl)){
			this.code = CODE.CONTROL;
			return;
		}
		// check servlet
		String match = servletContainer.matchUrlPattern(reqUrl);
		if (match != null) {
			this.code = CODE.SERVLET;
			return;
		}
		String tmp = HttpServer.rootDir + reqUrl;
///		System.out.println(tmp);
		File file = new File(tmp);
		if (!file.exists()) {
			this.code = CODE.NOFOUND;
			return;
		}
		if (file.isDirectory()) {
			this.code = CODE.LISTDIR;
		}
		if (file.isFile()) {
			this.code = CODE.FILE;
		}
	}
	
	//parse the request url
	private String simplifyPath(String path) {
        if (path == null)   return null;
        int level = 0;
        Deque<String> st = new ArrayDeque<String>();
        String[] folder = path.split("/");
        for (int i = 0; i < folder.length; i++) {
             if (".".equals(folder[i]) || "".equals(folder[i])) continue;
             if ("..".equals(folder[i])){
                 level--;
                 st.pollLast();         //return null if empty
             }else{
                 if (level >= 0)
                    st.offer(folder[i]);
                 level++;
             }    
        }
        if (level < 0)	return null;
        StringBuilder sb = new StringBuilder();
        sb.append("/");
        while (st.size() > 0){
            sb.append(st.pollFirst());
            sb.append("/");
        }
        if (sb.length() > 1)    sb.deleteCharAt(sb.length() - 1);
        return sb.toString();
    }
	
	// parse the request url
	private String parseURL(String dir) {			// /favicon.ico
		if (dir == null)	return null;
		String prefix = HttpServer.rootDir;
		dir = prefix + "/" + dir;
		String newDir = simplifyPath(dir);
//		System.out.println(newDir);
		if (newDir == null || newDir.length() < prefix.length())	//should at least equals to prefix
			return null;
		String newPrefix = newDir.substring(0, prefix.length());	//should have the same root directory
		if (!newPrefix.equals(prefix)) {
			return null;
		}
		newDir = newDir.substring(prefix.length());
		if (newDir.length() == 0)	newDir = "/";
		return newDir;
	}
	
	private void parseInitialLine(String line) throws UnsupportedEncodingException{	
		if (line == null){	
			this.code = CODE.BADREQ;
			return;
		}
		String[] tmp = line.trim().replaceAll(" +", " ").split(" ");			//be tolerant to input
		if (tmp.length != 3){
			this.code = CODE.BADREQ;
			return;
		}else{
			method = tmp[0];
			reqUrl = java.net.URLDecoder.decode(tmp[1], "UTF-8");
			protocol = tmp[2];
		}
	}
	
}
