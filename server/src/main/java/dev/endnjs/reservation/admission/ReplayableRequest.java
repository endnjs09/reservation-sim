package dev.endnjs.reservation.admission;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class ReplayableRequest extends HttpServletRequestWrapper {
    private final byte[] body;
    public ReplayableRequest(HttpServletRequest request) throws IOException { super(request); body=request.getInputStream().readAllBytes(); }
    public byte[] body() { return body; }
    @Override public ServletInputStream getInputStream() {
        var input=new ByteArrayInputStream(body);
        return new ServletInputStream() {
            public int read() { return input.read(); }
            public boolean isFinished() { return input.available()==0; }
            public boolean isReady() { return true; }
            public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous request body"); }
        };
    }
    @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8)); }
}
