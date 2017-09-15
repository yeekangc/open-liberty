/*******************************************************************************
 * Copyright (c) 2012 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ws.security.authentication.internal.jaas.modules;

import java.io.IOException;
import java.security.AccessController;
import java.security.Principal;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Set;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;

import com.ibm.websphere.ras.Tr;
import com.ibm.websphere.ras.TraceComponent;
import com.ibm.websphere.security.cred.WSCredential;
import com.ibm.ws.security.authentication.AuthenticationConstants;
import com.ibm.ws.security.authentication.AuthenticationService;
import com.ibm.ws.security.authentication.collective.CollectiveAuthenticationPlugin;
import com.ibm.ws.security.authentication.internal.SSOTokenHelper;
import com.ibm.ws.security.authentication.internal.jaas.JAASServiceImpl;
import com.ibm.ws.security.authentication.principals.WSPrincipal;
import com.ibm.ws.security.authentication.utility.SubjectHelper;
import com.ibm.ws.security.credentials.CredentialsService;
import com.ibm.ws.security.credentials.ExpirableCredential;
import com.ibm.ws.security.jaas.common.modules.CommonLoginModule;
import com.ibm.ws.security.registry.EntryNotFoundException;
import com.ibm.ws.security.registry.RegistryException;
import com.ibm.ws.security.registry.UserRegistry;
import com.ibm.ws.security.token.TokenManager;
import com.ibm.wsspi.security.auth.callback.Constants;
import com.ibm.wsspi.security.token.SingleSignonToken;

/**
 * Common login module logic needed by all WAS login modules.
 */
public abstract class ServerCommonLoginModule extends CommonLoginModule implements LoginModule {
    private static final TraceComponent tc = Tr.register(ServerCommonLoginModule.class);

    protected SubjectHelper subjectHelper = new SubjectHelper();

    protected CollectiveAuthenticationPlugin getCollectiveAuthenticationPlugin() throws RegistryException {
        return JAASServiceImpl.getCollectiveAuthenticationPlugin();
    }

    protected UserRegistry getUserRegistry() throws RegistryException {
        return JAASServiceImpl.getUserRegistry();
    }

    protected TokenManager getTokenManager() {
        return JAASServiceImpl.getTokenManager();
    }

    CredentialsService getCredentialsService() {
        return JAASServiceImpl.getCredentialsService();
    }

    protected AuthenticationService getAuthenticationService() {
        return JAASServiceImpl.getAuthenticationService();
    }

    /**
     * Gets the required Callback objects needed by this login module.
     *
     * @param callbackHandler
     * @return
     * @throws IOException
     * @throws UnsupportedCallbackException
     */
    public abstract Callback[] getRequiredCallbacks(CallbackHandler callbackHandler) throws IOException, UnsupportedCallbackException;

    /**
     * Common method called by all login modules that use the UserRegistry (UsernameAndPasswordLoginModule,
     * CertificateLoginModule, HashtableLoginModule and TokenLoginModule). Determines the securityName to use
     * for the login.
     *
     * @param loginName The username passed to the login
     * @param urAuthenticatedId The id returned by UserRegistry checkPassword or mapCertificate.
     *
     * @return The securityName to use for the WSPrincipal.
     *
     * @throws EntryNotFoundException
     * @throws RegistryException
     */
    protected String getSecurityName(String loginName, String urAuthenticatedId) throws EntryNotFoundException, RegistryException {

        UserRegistry ur = getUserRegistry();
        if (ur != null && ur.getType() != "CUSTOM") { // Preserve the existing behavior for CUSTOM user registries
            String securityName = ur.getUserSecurityName(urAuthenticatedId);
            if (securityName != null) {
                return securityName;
            }
        }

        // If a loginName was provided, use it.
        if (loginName != null) {
            return loginName;
        }

        if (ur != null) {
            return ur.getUserSecurityName(urAuthenticatedId);
        } else {
            throw new NullPointerException("No user registry");
        }
    }

    /**
     * Set the relevant Principal for this login module into the Subject,
     * and set the credentials for the determined accessId.
     *
     * @throws Exception
     */
    protected void setPrincipalAndCredentials(Subject subject,
                                              String securityName,
                                              String urAuthenticatedId,
                                              String accessId,
                                              String authMethod) throws Exception {
        Principal principal = new WSPrincipal(securityName, accessId, authMethod);
        subject.getPrincipals().add(principal);
        if (urAuthenticatedId != null && !urAuthenticatedId.equals(securityName)) {
            Hashtable<String, String> subjectHash = new Hashtable<String, String>();
            subjectHash.put(AuthenticationConstants.UR_AUTHENTICATED_USERID_KEY, urAuthenticatedId);
            subject.getPrivateCredentials().add(subjectHash);
        }
        CredentialsService credentialsService = getCredentialsService();
        credentialsService.setCredentials(subject);
    }

    /**
     * Gets the SSO token from the subject.
     *
     * @param subject {@code null} is not supported.
     * @return
     */
    protected SingleSignonToken getSSOToken(Subject subject) {
        return SSOTokenHelper.getSSOToken(subject);
    }

    /**
     * Sets the shared state with the WSPrincipal, WSCredential, and SSO token.
     * Subclasses can override this method to add specialized entries to the shared state.
     */
    protected void updateSharedState() {
        updateSharedStateWithWSPrincipal();
        updateSharedStateWithWSCredential();
        updateSharedStateWithSSOToken();
    }

    private void updateSharedStateWithWSPrincipal() {
        Set<WSPrincipal> principals = temporarySubject.getPrincipals(WSPrincipal.class);
        Iterator<WSPrincipal> principalsIterator = principals.iterator();
        if (principalsIterator.hasNext()) {
            WSPrincipal principal = principalsIterator.next();
            sharedState.put(Constants.WSPRINCIPAL_KEY, principal);
        }
    }

    private void updateSharedStateWithWSCredential() {
        SubjectHelper subjectHelper = new SubjectHelper();
        WSCredential wsCredential = subjectHelper.getWSCredential(temporarySubject);
        if (wsCredential != null) {
            sharedState.put(Constants.WSCREDENTIAL_KEY, wsCredential);
        }
    }

    private void updateSharedStateWithSSOToken() {
        SingleSignonToken ssoToken = getSSOToken(temporarySubject);
        if (ssoToken != null) {
            sharedState.put(Constants.WSSSOTOKEN_KEY, ssoToken);
        }
    }

    /**
     * Commit the newly created elements into the original Subject.
     */
    protected void setUpSubject() throws LoginException {
        try {
            AccessController.doPrivileged(new PrivilegedExceptionAction<Object>() {
                @Override
                public Object run() throws Exception {
                    updateSubjectWithSharedStateContents();
                    updateSubjectWithTemporarySubjectContents();
                    optionallySetWSCredentialExpiration();
                    return null;
                }
            });
        } catch (PrivilegedActionException e) {
            throw new LoginException("Unable to setup the Subject: " + e.getLocalizedMessage());
        }
    }

    /**
     * Sets the original subject with the shared state contents.
     */
    protected void updateSubjectWithSharedStateContents() {
        subject.getPrincipals().add((WSPrincipal) sharedState.get(Constants.WSPRINCIPAL_KEY));
        subject.getPublicCredentials().add(sharedState.get(Constants.WSCREDENTIAL_KEY));
        if (sharedState.get(Constants.WSSSOTOKEN_KEY) != null)
            subject.getPrivateCredentials().add(sharedState.get(Constants.WSSSOTOKEN_KEY));
    }

    /**
     * Sets the subject with the temporary subject contents that was not set already from the
     * shared state.
     */
    protected void updateSubjectWithTemporarySubjectContents() {
        updateSubjectWithPrincipalsOtherThanWSPrincipal();
        updateSubjectWithPublicCredentialsOtherThanWSCredential();
        updateSubjectWithPrivateCredentialsOtherThanSSOToken();
    }

    private void updateSubjectWithPrincipalsOtherThanWSPrincipal() {
        Set<WSPrincipal> temporaryWSPrincipals = temporarySubject.getPrincipals(WSPrincipal.class);
        Set<Principal> temporaryPrincipals = temporarySubject.getPrincipals();
        temporaryPrincipals.removeAll(temporaryWSPrincipals);
        subject.getPrincipals().addAll(temporaryPrincipals);
    }

    private void updateSubjectWithPublicCredentialsOtherThanWSCredential() {
        Set<WSCredential> temporaryWSCredentials = temporarySubject.getPublicCredentials(WSCredential.class);
        Set<Object> temporaryPublicCredentials = temporarySubject.getPublicCredentials();
        temporaryPublicCredentials.removeAll(temporaryWSCredentials);
        subject.getPublicCredentials().addAll(temporaryPublicCredentials);
    }

    private void updateSubjectWithPrivateCredentialsOtherThanSSOToken() {
        SingleSignonToken ssoToken = getSSOToken(temporarySubject);
        Set<Object> privateCredentials = temporarySubject.getPrivateCredentials();
        if (ssoToken != null)
            privateCredentials.remove(ssoToken);
        subject.getPrivateCredentials().addAll(privateCredentials);
    }

    private void optionallySetWSCredentialExpiration() {
        WSCredential wsCredential = subjectHelper.getWSCredential(subject);
        SingleSignonToken ssoToken = getSSOToken(subject);
        if (ssoToken != null && wsCredential instanceof ExpirableCredential) {
            ((ExpirableCredential) wsCredential).setExpiration(ssoToken.getExpiration());
        }
    }

    /**
     * Common Subject set up. Guarantees an atomic commit to the subject
     * passed in via initialization.
     *
     * @throws LoginException
     */
    void setUpSubject(final String securityName, final String accessId,
                      final String authMethod) throws LoginException {
        // Populate a temporary subject in response to a successful authentication.
        // We use a temporary Subject because if something goes wrong in this flow,
        // we are not updating the "live" Subject.
        try {
            AccessController.doPrivileged(new PrivilegedExceptionAction<Object>() {
                @Override
                public Object run() throws Exception {
                    temporarySubject = new Subject();
                    setPrincipalAndCredentials(temporarySubject, securityName, null, accessId, authMethod);

                    // Commit the newly created elements into the original Subject
                    subject.getPrincipals().addAll(temporarySubject.getPrincipals());
                    subject.getPublicCredentials().addAll(temporarySubject.getPublicCredentials());
                    subject.getPrivateCredentials().addAll(temporarySubject.getPrivateCredentials());

                    return null;
                }

            });
        } catch (PrivilegedActionException e) {
            throw new LoginException(e.getLocalizedMessage());
        }
    }

    protected boolean isAlreadyProcessed() {

        if (sharedState != null && "true".equalsIgnoreCase((String) sharedState.get(Constants.ALREADY_PROCESSED)))
            return true;
        else
            return false;
    }
}