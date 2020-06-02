package com.launchdarkly.eventsource;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An {@link SSLSocketFactory} that tries to ensure modern TLS versions are used.
 */
public class ModernTLSSocketFactory extends SSLSocketFactory {
  private static final String TLS_1_2 = "TLSv1.2";
  private static final String TLS_1_1 = "TLSv1.1";
  private static final String TLS_1 = "TLSv1";

  private SSLSocketFactory defaultSocketFactory;

  ModernTLSSocketFactory() throws NoSuchAlgorithmException, KeyManagementException {
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, null, null);
    this.defaultSocketFactory = context.getSocketFactory();
  }

  // COVERAGE: none of the following methods are exercised by the unit tests currently, because our
  // test support code is not able to run an embedded secure server with a self-signed certificate.
  
  @Override
  public String[] getDefaultCipherSuites() {
    return this.defaultSocketFactory.getDefaultCipherSuites();
  }

  @Override
  public String[] getSupportedCipherSuites() {
    return this.defaultSocketFactory.getSupportedCipherSuites();
  }

  @Override
  public Socket createSocket(Socket socket, String s, int i, boolean b) throws IOException {
    return setModernTlsVersionsOnSocket(this.defaultSocketFactory.createSocket(socket, s, i, b));
  }

  @Override
  public Socket createSocket(String s, int i) throws IOException, UnknownHostException {
    return setModernTlsVersionsOnSocket(this.defaultSocketFactory.createSocket(s, i));
  }

  @Override
  public Socket createSocket(String s, int i, InetAddress inetAddress, int i1) throws IOException, UnknownHostException {
    return setModernTlsVersionsOnSocket(this.defaultSocketFactory.createSocket(s, i, inetAddress, i1));
  }

  @Override
  public Socket createSocket(InetAddress inetAddress, int i) throws IOException {
    return setModernTlsVersionsOnSocket(this.defaultSocketFactory.createSocket(inetAddress, i));
  }

  @Override
  public Socket createSocket(InetAddress inetAddress, int i, InetAddress inetAddress1, int i1) throws IOException {
    return setModernTlsVersionsOnSocket(this.defaultSocketFactory.createSocket(inetAddress, i, inetAddress1, i1));
  }

  /**
   * If either of TLSv1.2, TLSv1.1, or TLSv1 are supported, make them the only enabled protocols (listing in that order).
   * <p>
   * If the socket does not make these modern TLS protocols available at all, then just return the socket unchanged.
   *
   * @param s the socket
   * @return
   */
  static Socket setModernTlsVersionsOnSocket(Socket s) {
    if (s != null && (s instanceof SSLSocket)) {
      List<String> defaultEnabledProtocols = Arrays.asList(((SSLSocket) s).getSupportedProtocols());
      ArrayList<String> newEnabledProtocols = new ArrayList<>();
      if (defaultEnabledProtocols.contains(TLS_1_2)) {
        newEnabledProtocols.add(TLS_1_2);
      }
      if (defaultEnabledProtocols.contains(TLS_1_1)) {
        newEnabledProtocols.add(TLS_1_1);
      }
      if (defaultEnabledProtocols.contains(TLS_1)) {
        newEnabledProtocols.add(TLS_1);
      }
      if (newEnabledProtocols.size() > 0) {
        ((SSLSocket) s).setEnabledProtocols(newEnabledProtocols.toArray(new String[newEnabledProtocols.size()]));
      }
    }
    return s;
  }
}
