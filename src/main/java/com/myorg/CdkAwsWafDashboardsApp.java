package com.myorg;

import software.amazon.awscdk.core.App;
import software.amazon.awscdk.core.StackProps;

public class CdkAwsWafDashboardsApp {
    public static void main(final String[] args) {
        App app = new App();

        String stackName = "OpenSearch Dashboards for WAF";
        MainStack openSearch = new MainStack(app, "OSDfW", StackProps.builder().build());

        app.synth();
    }
}
