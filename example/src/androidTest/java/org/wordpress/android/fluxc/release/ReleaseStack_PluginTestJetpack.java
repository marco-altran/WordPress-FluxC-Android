package org.wordpress.android.fluxc.release;

import org.greenrobot.eventbus.Subscribe;
import org.wordpress.android.fluxc.TestUtils;
import org.wordpress.android.fluxc.example.BuildConfig;
import org.wordpress.android.fluxc.generated.AccountActionBuilder;
import org.wordpress.android.fluxc.generated.AuthenticationActionBuilder;
import org.wordpress.android.fluxc.generated.PluginActionBuilder;
import org.wordpress.android.fluxc.generated.SiteActionBuilder;
import org.wordpress.android.fluxc.model.PluginModel;
import org.wordpress.android.fluxc.model.SiteModel;
import org.wordpress.android.fluxc.store.AccountStore;
import org.wordpress.android.fluxc.store.AccountStore.AuthenticatePayload;
import org.wordpress.android.fluxc.store.AccountStore.OnAccountChanged;
import org.wordpress.android.fluxc.store.AccountStore.OnAuthenticationChanged;
import org.wordpress.android.fluxc.store.PluginStore;
import org.wordpress.android.fluxc.store.PluginStore.DeleteSitePluginErrorType;
import org.wordpress.android.fluxc.store.PluginStore.DeleteSitePluginPayload;
import org.wordpress.android.fluxc.store.PluginStore.InstallSitePluginErrorType;
import org.wordpress.android.fluxc.store.PluginStore.InstallSitePluginPayload;
import org.wordpress.android.fluxc.store.PluginStore.OnSitePluginConfigured;
import org.wordpress.android.fluxc.store.PluginStore.OnSitePluginDeleted;
import org.wordpress.android.fluxc.store.PluginStore.OnSitePluginInstalled;
import org.wordpress.android.fluxc.store.PluginStore.OnSitePluginsFetched;
import org.wordpress.android.fluxc.store.PluginStore.UpdateSitePluginErrorType;
import org.wordpress.android.fluxc.store.PluginStore.UpdateSitePluginPayload;
import org.wordpress.android.fluxc.store.SiteStore;
import org.wordpress.android.fluxc.store.SiteStore.OnSiteChanged;
import org.wordpress.android.fluxc.store.SiteStore.OnSiteRemoved;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.AppLog.T;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

public class ReleaseStack_PluginTestJetpack extends ReleaseStack_Base {
    @Inject SiteStore mSiteStore;
    @Inject AccountStore mAccountStore;
    @Inject PluginStore mPluginStore;

    enum TestEvents {
        NONE,
        DELETE_PLUGIN_ERROR,
        DELETED_PLUGIN,
        INSTALLED_PLUGIN,
        INSTALL_PLUGIN_ERROR_NO_PACKAGE,
        PLUGINS_FETCHED,
        SITE_CHANGED,
        SITE_REMOVED,
        UNKNOWN_PLUGIN,
        UPDATED_PLUGIN
    }

    private TestEvents mNextEvent;
    private PluginModel mInstalledPlugin;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mReleaseStackAppComponent.inject(this);
        // Register
        init();
        // Reset expected test event
        mNextEvent = TestEvents.NONE;
        mInstalledPlugin = null;
    }

    public void testFetchSitePlugins() throws InterruptedException {
        SiteModel site = fetchSingleJetpackSitePlugins();

        List<PluginModel> plugins = mPluginStore.getSitePlugins(site);
        assertTrue(plugins.size() > 0);

        signOutWPCom();
    }

    public void testUpdateSitePlugin() throws InterruptedException {
        // In order to have a reliable test, let's first fetch the list of plugins, pick the first plugin
        // and change it's active status, so we can make sure when we run the test multiple times, each time
        // an action is actually taken. This wouldn't be the case if we always activate the plugin.
        SiteModel site = fetchSingleJetpackSitePlugins();

        List<PluginModel> plugins = mPluginStore.getSitePlugins(site);
        assertTrue(plugins.size() > 0);
        PluginModel plugin = plugins.get(0);
        boolean isActive = !plugin.isActive();
        plugin.setIsActive(isActive);

        mNextEvent = TestEvents.UPDATED_PLUGIN;
        mCountDownLatch = new CountDownLatch(1);

        UpdateSitePluginPayload payload = new UpdateSitePluginPayload(site, plugin);
        mDispatcher.dispatch(PluginActionBuilder.newConfigureSitePluginAction(payload));

        assertTrue(mCountDownLatch.await(TestUtils.DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS));

        PluginModel newPlugin = mPluginStore.getSitePluginByName(site, plugin.getName());
        assertNotNull(newPlugin);
        assertEquals(newPlugin.isActive(), isActive);

        signOutWPCom();
    }

    // It's both easier and more efficient to combine install & delete tests since we need to make sure we have the
    // plugin installed for the delete test and the plugin is not installed for the install test
    public void testInstallAndDeleteSitePlugin() throws InterruptedException {
        String pluginSlugToInstall = "react";
        // Fetch the list of installed plugins to make sure `React` is not installed
        SiteModel site = fetchSingleJetpackSitePlugins();

        List<PluginModel> sitePlugins = mPluginStore.getSitePlugins(site);
        for (PluginModel sitePlugin : sitePlugins) {
            if (sitePlugin.getSlug().equals(pluginSlugToInstall)) {
                // We need to deactivate the plugin to be able to uninstall it
                if (sitePlugin.isActive()) {
                    deactivatePlugin(site, sitePlugin);
                }

                // delete plugin first
                deleteSitePlugin(site, sitePlugin);
            }
        }

        // Install the React plugin
        installSitePlugin(site, pluginSlugToInstall);

        // mInstalledPlugin should be set in onSitePluginInstalled
        assertNotNull(mInstalledPlugin);

        // We need to deactivate the plugin to be able to uninstall it
        if (mInstalledPlugin.isActive()) {
            deactivatePlugin(site, mInstalledPlugin);
        }

        // Delete the newly installed React plugin
        deleteSitePlugin(site, mInstalledPlugin);

        List<PluginModel> updatedPlugins = mPluginStore.getSitePlugins(site);
        for (PluginModel sitePlugin : updatedPlugins) {
            assertFalse(sitePlugin.getSlug().equals(pluginSlugToInstall));
        }
    }

    public void testUnknownPluginError() throws InterruptedException {
        SiteModel site = authenticateAndRetrieveSingleJetpackSite();

        PluginModel plugin = new PluginModel();
        plugin.setName("this-plugin-does-not-exist");

        mNextEvent = TestEvents.UNKNOWN_PLUGIN;
        mCountDownLatch = new CountDownLatch(1);

        UpdateSitePluginPayload payload = new UpdateSitePluginPayload(site, plugin);
        mDispatcher.dispatch(PluginActionBuilder.newConfigureSitePluginAction(payload));

        assertTrue(mCountDownLatch.await(TestUtils.DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    public void testDeleteActivePluginError() throws InterruptedException {
        SiteModel site = fetchSingleJetpackSitePlugins();

        PluginModel activePluginToTest = null;

        List<PluginModel> sitePlugins = mPluginStore.getSitePlugins(site);
        for (PluginModel sitePlugin : sitePlugins) {
            if (sitePlugin.isActive()) {
                activePluginToTest = sitePlugin;
                break;
            }
        }

        // Trying to delete an active plugin should result in DELETE_PLUGIN_ERROR
        deleteSitePlugin(site, activePluginToTest, TestEvents.DELETE_PLUGIN_ERROR);
    }

    public void testInstallPluginNoPackageError() throws InterruptedException {
        SiteModel site = fetchSingleJetpackSitePlugins();
        installSitePlugin(site, "this-plugin-does-not-exist", TestEvents.INSTALL_PLUGIN_ERROR_NO_PACKAGE);
        assertNull(mInstalledPlugin);
    }

    @SuppressWarnings("unused")
    @Subscribe
    public void onAuthenticationChanged(OnAuthenticationChanged event) {
        if (event.isError()) {
            throw new AssertionError("Unexpected error occurred with type: " + event.error.type);
        }
        mCountDownLatch.countDown();
    }

    @SuppressWarnings("unused")
    @Subscribe
    public void onAccountChanged(OnAccountChanged event) {
        AppLog.d(T.TESTS, "Received OnAccountChanged event");
        if (event.isError()) {
            throw new AssertionError("Unexpected error occurred with type: " + event.error.type);
        }
        mCountDownLatch.countDown();
    }

    @SuppressWarnings("unused")
    @Subscribe
    public void onSiteChanged(OnSiteChanged event) {
        AppLog.i(T.TESTS, "site count " + mSiteStore.getSitesCount());
        if (event.isError()) {
            throw new AssertionError("Unexpected error occurred with type: " + event.error.type);
        }
        assertTrue(mSiteStore.hasSite());
        assertEquals(TestEvents.SITE_CHANGED, mNextEvent);
        mCountDownLatch.countDown();
    }

    @SuppressWarnings("unused")
    @Subscribe
    public void onSiteRemoved(OnSiteRemoved event) {
        AppLog.d(T.TESTS, "site count " + mSiteStore.getSitesCount());
        if (event.isError()) {
            throw new AssertionError("Unexpected error occurred with type: " + event.error.type);
        }
        assertEquals(TestEvents.SITE_REMOVED, mNextEvent);
        mCountDownLatch.countDown();
    }

    @SuppressWarnings("unused")
    @Subscribe
    public void onSitePluginsFetched(OnSitePluginsFetched event) {
        AppLog.i(T.API, "Received onSitePluginsFetched");
        if (event.isError()) {
            throw new AssertionError("Unexpected error occurred in onSitePluginsFetched with type: "
                    + event.error.type);
        }
        assertEquals(mNextEvent, TestEvents.PLUGINS_FETCHED);
        mCountDownLatch.countDown();
    }

    @SuppressWarnings("unused")
    @Subscribe
    public void onSitePluginConfigured(OnSitePluginConfigured event) {
        AppLog.i(T.API, "Received onSitePluginConfigured");
        if (event.isError()) {
            if (event.error.type.equals(UpdateSitePluginErrorType.UNKNOWN_PLUGIN)) {
                assertEquals(mNextEvent, TestEvents.UNKNOWN_PLUGIN);
            } else {
                throw new AssertionError("Unexpected error occurred in onSitePluginConfigured with type: "
                        + event.error.type);
            }
        } else {
            assertEquals(mNextEvent, TestEvents.UPDATED_PLUGIN);
        }
        mCountDownLatch.countDown();
    }

    @SuppressWarnings("unused")
    @Subscribe
    public void onSitePluginDeleted(OnSitePluginDeleted event) {
        AppLog.i(T.API, "Received onSitePluginDeleted");
        if (event.isError()) {
            if (event.error.type.equals(DeleteSitePluginErrorType.DELETE_PLUGIN_ERROR)) {
                assertEquals(mNextEvent, TestEvents.DELETE_PLUGIN_ERROR);
            } else {
                throw new AssertionError("Unexpected error occurred in onSitePluginDeleted with type: "
                        + event.error.type);
            }
        } else {
            assertEquals(mNextEvent, TestEvents.DELETED_PLUGIN);
        }
        mCountDownLatch.countDown();
    }

    @SuppressWarnings("unused")
    @Subscribe
    public void onSitePluginInstalled(OnSitePluginInstalled event) {
        AppLog.i(T.API, "Received onSitePluginInstalled");
        if (event.isError()) {
            if (event.error.type.equals(InstallSitePluginErrorType.NO_PACKAGE)) {
                assertEquals(mNextEvent, TestEvents.INSTALL_PLUGIN_ERROR_NO_PACKAGE);
            } else {
                throw new AssertionError("Unexpected error occurred in onSitePluginInstalled with type: "
                        + event.error.type);
            }
        } else {
            assertEquals(mNextEvent, TestEvents.INSTALLED_PLUGIN);
        }
        mInstalledPlugin = event.plugin;
        mCountDownLatch.countDown();
    }

    private void authenticateWPComAndFetchSites(String username, String password) throws InterruptedException {
        // Authenticate a test user (actual credentials declared in gradle.properties)
        AuthenticatePayload payload = new AuthenticatePayload(username, password);
        mCountDownLatch = new CountDownLatch(1);

        // Correct user we should get an OnAuthenticationChanged message
        mDispatcher.dispatch(AuthenticationActionBuilder.newAuthenticateAction(payload));
        // Wait for a network response / onChanged event
        assertTrue(mCountDownLatch.await(TestUtils.DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS));

        // Fetch account from REST API, and wait for OnAccountChanged event
        mCountDownLatch = new CountDownLatch(1);
        mDispatcher.dispatch(AccountActionBuilder.newFetchAccountAction());
        assertTrue(mCountDownLatch.await(TestUtils.DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS));

        // Fetch sites from REST API, and wait for onSiteChanged event
        mCountDownLatch = new CountDownLatch(1);
        mNextEvent = TestEvents.SITE_CHANGED;
        mDispatcher.dispatch(SiteActionBuilder.newFetchSitesAction());

        assertTrue(mCountDownLatch.await(TestUtils.DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertTrue(mSiteStore.getSitesCount() > 0);
    }

    private void signOutWPCom() throws InterruptedException {
        // Clear WP.com sites, and wait for OnSiteRemoved event
        mCountDownLatch = new CountDownLatch(1);
        mNextEvent = TestEvents.SITE_REMOVED;
        mDispatcher.dispatch(SiteActionBuilder.newRemoveWpcomAndJetpackSitesAction());

        assertTrue(mCountDownLatch.await(TestUtils.DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    private SiteModel fetchSingleJetpackSitePlugins() throws InterruptedException {
        SiteModel site = authenticateAndRetrieveSingleJetpackSite();

        mNextEvent = TestEvents.PLUGINS_FETCHED;
        mCountDownLatch = new CountDownLatch(1);
        mDispatcher.dispatch(PluginActionBuilder.newFetchSitePluginsAction(site));

        assertTrue(mCountDownLatch.await(TestUtils.DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS));

        return site;
    }

    private SiteModel authenticateAndRetrieveSingleJetpackSite() throws InterruptedException {
        authenticateWPComAndFetchSites(BuildConfig.TEST_WPCOM_USERNAME_JETPACK_BETA_SITE,
                BuildConfig.TEST_WPCOM_PASSWORD_JETPACK_BETA_SITE);
        return mSiteStore.getSites().get(0);
    }

    private void deleteSitePlugin(SiteModel site, PluginModel plugin) throws InterruptedException {
        deleteSitePlugin(site, plugin, TestEvents.DELETED_PLUGIN);
    }

    private void deleteSitePlugin(SiteModel site, PluginModel plugin,
                                  TestEvents testEvent) throws InterruptedException {
        mDispatcher.dispatch(PluginActionBuilder.newDeleteSitePluginAction(
                new DeleteSitePluginPayload(site, plugin)));
        mNextEvent = testEvent;
        mCountDownLatch = new CountDownLatch(1);
        assertTrue(mCountDownLatch.await(TestUtils.DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    private void installSitePlugin(SiteModel site, String pluginSlug) throws InterruptedException {
        installSitePlugin(site, pluginSlug, TestEvents.INSTALLED_PLUGIN);
    }

    private void installSitePlugin(SiteModel site, String pluginSlug,
                                   TestEvents testEvent) throws InterruptedException {
        mDispatcher.dispatch(PluginActionBuilder.newInstallSitePluginAction(
                new InstallSitePluginPayload(site, pluginSlug)));
        mNextEvent = testEvent;
        mCountDownLatch = new CountDownLatch(1);
        assertTrue(mCountDownLatch.await(TestUtils.DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    private void deactivatePlugin(SiteModel site, PluginModel plugin) throws InterruptedException {
        mNextEvent = TestEvents.UPDATED_PLUGIN;
        mCountDownLatch = new CountDownLatch(1);

        plugin.setIsActive(false);
        UpdateSitePluginPayload payload = new UpdateSitePluginPayload(site, plugin);
        mDispatcher.dispatch(PluginActionBuilder.newConfigureSitePluginAction(payload));

        assertTrue(mCountDownLatch.await(TestUtils.DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }
}
