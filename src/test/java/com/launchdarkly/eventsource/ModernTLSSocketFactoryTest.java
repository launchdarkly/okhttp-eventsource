package com.launchdarkly.eventsource;

import org.junit.Test;

import java.io.IOException;

import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@SuppressWarnings("javadoc")
public class ModernTLSSocketFactoryTest {
  @Test
  public void allModernTLSVersionsAddedWhenAvailable() {
    MockSSLSocket s = new MockSSLSocket();
    s.supportedProtocols =
        new String[]{"SSL", "SSLv2", "SSLv3", "TLS", "TLSv1", "TLSv1.1", "TLSv1.2"};

    ModernTLSSocketFactory.setModernTlsVersionsOnSocket(s);
    assertThat(s.enabledProtocols, equalTo(new String[]{"TLSv1.2", "TLSv1.1", "TLSv1"}));
  }

  @Test
  public void oneModernTLSVersionsAddedWhenAvailable() {
    MockSSLSocket s = new MockSSLSocket();
    s.supportedProtocols =
        new String[]{"SSL", "SSLv2", "SSLv3", "TLS", "TLSv1"};

    ModernTLSSocketFactory.setModernTlsVersionsOnSocket(s);
    assertThat(s.enabledProtocols, equalTo(new String[]{"TLSv1"}));
  }

  @Test
  public void enabledProtocolsUntouchedWhenNoModernProtocolsAvailable() {
    MockSSLSocket s = new MockSSLSocket();
    s.supportedProtocols =
        new String[]{"SSL", "SSLv2", "SSLv3", "TLS"};

    ModernTLSSocketFactory.setModernTlsVersionsOnSocket(s);
    assertThat(s.enabledProtocols, nullValue());
  }
  
  private static class MockSSLSocket extends SSLSocket {
    String[] enabledProtocols;
    String[] supportedProtocols;
    
    @Override
    public void addHandshakeCompletedListener(HandshakeCompletedListener arg0) {
    }

    @Override
    public boolean getEnableSessionCreation() {
      return false;
    }

    @Override
    public String[] getEnabledCipherSuites() {
      return null;
    }

    @Override
    public String[] getEnabledProtocols() {
      return enabledProtocols;
    }

    @Override
    public boolean getNeedClientAuth() {
      return false;
    }

    @Override
    public SSLSession getSession() {
      return null;
    }

    @Override
    public String[] getSupportedCipherSuites() {
      return null;
    }

    @Override
    public String[] getSupportedProtocols() {
      return supportedProtocols;
    }

    @Override
    public boolean getUseClientMode() {
      return false;
    }

    @Override
    public boolean getWantClientAuth() {
      return false;
    }

    @Override
    public void removeHandshakeCompletedListener(HandshakeCompletedListener arg0) {
    }

    @Override
    public void setEnableSessionCreation(boolean arg0) {
    }

    @Override
    public void setEnabledCipherSuites(String[] arg0) {
    }

    @Override
    public void setEnabledProtocols(String[] arg0) {
      enabledProtocols = arg0;
    }

    @Override
    public void setNeedClientAuth(boolean arg0) {
    }

    @Override
    public void setUseClientMode(boolean arg0) {
    }

    @Override
    public void setWantClientAuth(boolean arg0) {
    }

    @Override
    public void startHandshake() throws IOException {
    }    
  }
}
