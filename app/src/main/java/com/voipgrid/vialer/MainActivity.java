package com.voipgrid.vialer;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.TabLayout;
import android.support.v4.app.ActivityOptionsCompat;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentStatePagerAdapter;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.ViewPager;
import android.view.View;

import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;
import com.voipgrid.vialer.analytics.AnalyticsApplication;
import com.voipgrid.vialer.analytics.AnalyticsHelper;
import com.voipgrid.vialer.api.ApiTokenFetcher;
import com.voipgrid.vialer.api.models.SystemUser;
import com.voipgrid.vialer.callrecord.CallRecordFragment;
import com.voipgrid.vialer.contacts.SyncUtils;
import com.voipgrid.vialer.contacts.UpdateChangedContactsService;
import com.voipgrid.vialer.dialer.DialerActivity;
import com.voipgrid.vialer.logging.RemoteLogger;
import com.voipgrid.vialer.onboarding.AccountFragment;
import com.voipgrid.vialer.onboarding.SetupActivity;
import com.voipgrid.vialer.permissions.ContactsPermission;
import com.voipgrid.vialer.permissions.PhonePermission;
import com.voipgrid.vialer.permissions.ReadExternalStoragePermission;
import com.voipgrid.vialer.reachability.ReachabilityReceiver;
import com.voipgrid.vialer.util.ConnectivityHelper;
import com.voipgrid.vialer.util.DialHelper;
import com.voipgrid.vialer.util.JsonStorage;
import com.voipgrid.vialer.util.PhoneAccountHelper;
import com.voipgrid.vialer.util.UpdateActivity;
import com.voipgrid.vialer.util.UpdateHelper;


public class MainActivity extends NavigationDrawerActivity implements
        View.OnClickListener,
        CallRecordFragment.OnFragmentInteractionListener {

    private ViewPager mViewPager;
    private View mView;
    private boolean mAskForPermission = true;
    private int requestCounter = -1;
    private RemoteLogger mRemoteLogger;
    private DialHelper mDialHelper;

    private ReachabilityReceiver mReachabilityReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle startBundle = getIntent().getExtras();
        if (startBundle != null) {
            boolean onBoot = startBundle.getBoolean("OnBoot");
            if (onBoot) {
                finish();
                return;
            }
        }

        mRemoteLogger = new RemoteLogger(this.getClass()).enableConsoleLogging();
        JsonStorage jsonStorage = new JsonStorage(this);
        ConnectivityHelper connectivityHelper = ConnectivityHelper.get(this);
        Boolean hasSystemUser = jsonStorage.has(SystemUser.class);
        SystemUser systemUser = (SystemUser) jsonStorage.get(SystemUser.class);
        mDialHelper = DialHelper.fromActivity(this);

        // Check if the app has a SystemUser.
        // When there is no SystemUser present start the on boarding process.
        // When there is a SystemUser but there is no mobile number configured go to the
        // on boarding part where the mobile number needs to be configured.
        if (!hasSystemUser) {
            // Start on boarding flow.
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        } else if (UpdateHelper.requiresUpdate(this)) {
            Intent intent = new Intent(this, UpdateActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
            return;
        } else if (systemUser.getMobileNumber() == null) {
            Intent intent = new Intent(this, SetupActivity.class);

            Bundle bundle = new Bundle();
            bundle.putInt("fragment", R.id.fragment_account);
            bundle.putString("activity", AccountFragment.class.getSimpleName());
            intent.putExtras(bundle);

            startActivity(intent);
            finish();
            return;
        } else if (connectivityHelper.hasNetworkConnection()) {
            fetchApiTokenIfDoesNotExist();

            // Update SystemUser and PhoneAccount on background thread.
            new PhoneAccountHelper(this).executeUpdatePhoneAccountTask();
        }

        if (SyncUtils.requiresFullContactSync(this)) {
            SyncUtils.requestContactSync(this);
        } else {
            startContactObserverService();
        }

        SyncUtils.setPeriodicSync(this);

        setContentView(R.layout.activity_main);

        // Set the Toolbar to use as ActionBar.
        setActionBar(R.id.action_bar);

        setNavigationDrawer(R.id.drawer_layout);

        // Set tabs.
        setupTabs();

        FloatingActionButton openDialerFab = findViewById(R.id.floating_action_button);
        openDialerFab.setOnClickListener(this);

        requestCounter = 0;
        mReachabilityReceiver = new ReachabilityReceiver(this);
    }

    /**
     * If we do not currently have an api token stored, fetch one from the server.
     *
     */
    private void fetchApiTokenIfDoesNotExist() {
        if (hasApiToken()) return;

        mRemoteLogger.i("There is no api-key currently stored, will attempt to fetch one");

        ApiTokenFetcher.usingSavedCredentials(this).setListener(new ApiTokenListener()).fetch();
    }

    private void askForPermissions(int requestNr) {
        switch (requestNr) {
            case 0:
                int storagePermissionState = ReadExternalStoragePermission.getPermissionStatus(this, ReadExternalStoragePermission.mPermissionToCheck);
                if (storagePermissionState != ReadExternalStoragePermission.BLOCKED || ReadExternalStoragePermission.firstRequest) {
                    if (!ReadExternalStoragePermission.hasPermission(this)) {
                        ReadExternalStoragePermission.askForPermission(this);
                        requestCounter++;
                        return;
                    }
                }
            case 1:
                // Ask for phone permissions.
                if (!PhonePermission.hasPermission(this)) {
                    PhonePermission.askForPermission(this);
                    requestCounter++;
                    return;
                }
            case 2:
                if (!ContactsPermission.hasPermission(this)) {
                    // We need to avoid a permission loop.
                    if (mAskForPermission) {
                        mAskForPermission = false;
                        ContactsPermission.askForPermission(this);
                        requestCounter++;
                    }
                }
        }
    }

    @Override
    protected void onResume() {
        askForPermissions(requestCounter);
        mReachabilityReceiver.startListening();
        super.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mReachabilityReceiver.stopListening();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (!allPermissionsGranted(permissions, grantResults)) {
            return;
        }
        if (requestCode == this.getResources().getInteger(R.integer.contact_permission_request_code)) {
            boolean allPermissionsGranted = true;
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false;
                    break;
                }
            }
            if (allPermissionsGranted) {
                // ContactSync.
                SyncUtils.requestContactSync(this);
                startContactObserverService();
            }
        } else if (requestCode == this.getResources().getInteger(R.integer.microphone_permission_request_code)) {
            mDialHelper.callAttemptedNumber();
        }
    }

    /**
     * Sets Tab Recents and Tab Contacts to the TabLayout
     */
    private void setupTabs() {
        TabLayout tabLayout = (TabLayout) findViewById(R.id.tab_layout);
        tabLayout.setTabTextColors(
                ContextCompat.getColor(this, R.color.tab_inactive),
                ContextCompat.getColor(this, R.color.tab_active));
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_title_recents));
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_title_missed));

        mViewPager = (ViewPager) findViewById(R.id.view_pager);
        mViewPager.addOnPageChangeListener(new TabLayout.TabLayoutOnPageChangeListener(tabLayout));
        mViewPager.setAdapter(new TabAdapter(getSupportFragmentManager()));

        tabLayout.setOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                mViewPager.setCurrentItem(tab.getPosition());

                // Get tracker.
                Tracker tracker = ((AnalyticsApplication) getApplication()).getDefaultTracker();

                // Set screen name.
                tracker.setScreenName(getScreenName(tab.getText().toString()));

                // Send a screen view.
                tracker.send(new HitBuilders.ScreenViewBuilder().build());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {

            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {

            }
        });
    }

    /**
     * Starts the service that will listen for changes to contacts.
     */
    private void startContactObserverService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2
                && ContactsPermission.hasPermission(this)) {
            startService(new Intent(this, UpdateChangedContactsService.class));
        }
    }

    private String getScreenName(String text) {
        if(text.equals(getString(R.string.tab_title_recents))) {
            return getString(R.string.analytics_screenname_recents);
        }
        if(text.equals(getString(R.string.tab_title_missed))) {
            return getString(R.string.analytics_screenname_missed);
        }
        return text;
    }

    /**
     * Handle onClick events within the MainActivity
     * @param view
     */
    @Override
    public void onClick(View view) {
        switch(view.getId()) {
            case R.id.floating_action_button : openDialer(); break;
        }
    }

    /**
     * Show the dialer view
     */
    public void openDialer() {
        Intent intent = new Intent(this, DialerActivity.class);
        if(Build.VERSION.SDK_INT > Build.VERSION_CODES.KITKAT) {
            ActivityOptionsCompat options = ActivityOptionsCompat.
                    makeSceneTransitionAnimation(
                            this, findViewById(R.id.floating_action_button),
                            "floating_action_button_transition_name");
            startActivity(intent, options.toBundle());
        } else {
            startActivity(intent);
        }
    }

    @Override
    public void onFragmentInteraction(String id) {

    }

    /**
     * Tab adapter to handle tabs in the ViewPager
     */
    public class TabAdapter extends FragmentStatePagerAdapter {

        public TabAdapter(FragmentManager fragmentManager) {
            super(fragmentManager);
        }

        @Override
        public Fragment getItem(int position) {
            if(position == 0) { // recents tab
                return CallRecordFragment.newInstance(null);
            }
            if(position == 1) { // missed tab
                return CallRecordFragment.newInstance(CallRecordFragment.FILTER_MISSED_RECORDS);
            }
            return TabFragment.newInstance("");
        }

        @Override
        public int getCount() {
            return 2;
        }
    }

    /**
     * Listen for the api token request and display a dialog to enter the two-factor token
     * if one is required.
     *
     */
    private class ApiTokenListener implements ApiTokenFetcher.ApiTokenListener {
        @Override
        public void twoFactorCodeRequired() {
            if(isFinishing()) {
                return;
            }

            mRemoteLogger.i("Prompting the user to enter a two-factor code");

            TwoFactorAuthenticationDialogFragment twoFactorAuthenticationDialogFragment = new TwoFactorAuthenticationDialogFragment();
            twoFactorAuthenticationDialogFragment.show(getFragmentManager(), "");
            twoFactorAuthenticationDialogFragment.setCancelable(false);
        }

        @Override
        public void onSuccess(String apiToken) {}

        @Override
        public void onFailure() {}
    }
}