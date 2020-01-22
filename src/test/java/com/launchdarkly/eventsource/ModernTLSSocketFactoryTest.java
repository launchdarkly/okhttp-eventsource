package com.launchdarkly.eventsource;

import org.junit.Test;

import javax.net.ssl.SSLSocket;

import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("javadoc")
public class ModernTLSSocketFactoryTest {
  @Test
  public void allModernTLSVersionsAddedWhenAvailable() {
    SSLSocket s = mock(SSLSocket.class);
    when(s.getSupportedProtocols())
        .thenReturn(new String[]{"SSL", "SSLv2", "SSLv3", "TLS", "TLSv1", "TLSv1.1", "TLSv1.2"});

    ModernTLSSocketFactory.setModernTlsVersionsOnSocket(s);
    verify(s).setEnabledProtocols(eq(new String[]{"TLSv1.2", "TLSv1.1", "TLSv1"}));
  }

  @Test
  public void oneModernTLSVersionsAddedWhenAvailable() {
    SSLSocket s = mock(SSLSocket.class);
    when(s.getSupportedProtocols())
        .thenReturn(new String[]{"SSL", "SSLv2", "SSLv3", "TLS", "TLSv1"});

    ModernTLSSocketFactory.setModernTlsVersionsOnSocket(s);
    verify(s).setEnabledProtocols(eq(new String[]{"TLSv1"}));
  }

  @Test
  public void enabledProtocolsUntouchedWhenNoModernProtocolsAvailable() {
    SSLSocket s = mock(SSLSocket.class);
    when(s.getSupportedProtocols())
        .thenReturn(new String[]{"SSL", "SSLv2", "SSLv3", "TLS"});

    ModernTLSSocketFactory.setModernTlsVersionsOnSocket(s);
    verify(s, never()).setEnabledProtocols(new String[]{});
  }
}
