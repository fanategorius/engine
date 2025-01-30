package org.ovirt.engine.core;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.ovirt.engine.core.branding.BrandingManager;
import org.ovirt.engine.core.common.interfaces.BackendLocal;
import org.ovirt.engine.core.common.queries.QueryReturnValue;
import org.ovirt.engine.core.common.queries.QueryType;
import org.ovirt.engine.core.utils.MockConfigExtension;
import org.ovirt.engine.core.utils.servlet.LocaleFilter;

@ExtendWith({MockitoExtension.class, MockConfigExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
public class WelcomeServletTest {
    @Spy
    WelcomeServlet testServlet;

    @Mock
    HttpServletRequest mockRequest;

    @Mock
    HttpSession mockSession;

    @Mock
    ServletContext mockContext;

    @Mock
    HttpServletResponse mockResponse;

    @Mock
    RequestDispatcher mockDispatcher;

    @Mock
    BackendLocal mockBackend;

    @Mock
    BrandingManager mockBrandingManager;

    final List<String> localeKeys = createLocaleKeys();

    private void mockBackendQuery(QueryType queryType, Object returnValue) {
        QueryReturnValue queryReturnValue = new QueryReturnValue();
        queryReturnValue.setReturnValue(returnValue);
        when(mockBackend.runPublicQuery(eq(queryType), any())).thenReturn(queryReturnValue);
    }

    @BeforeEach
    public void setUp() throws Exception {
        doReturn("http://localhost:8080/ovirt-engine/sso/credentials-change.html").when(testServlet).getCredentialsChangeUrl(any());
        testServlet.setBackend(mockBackend);
        testServlet.init(mockBrandingManager, "/ovirt-engine");
        mockBackendQuery(QueryType.GetConfigurationValue, "oVirtVersion");
        when(mockBrandingManager.getWelcomeSections(any())).thenReturn("Welcome Section HTML");
    }

     private List<String> createLocaleKeys() {
        List<String> keys = new ArrayList<>();
        keys.add("en_US");
        keys.add("ru");
        return keys;
    }
}
