package com.myorg;

import software.amazon.awscdk.core.NestedStackProps;
import software.amazon.awscdk.services.opensearchservice.Domain;

public class FirehoseNestedStackProps implements NestedStackProps {
    private final Domain openSearchDomain;

    public FirehoseNestedStackProps(Domain openSearchDomain) {
        this.openSearchDomain = openSearchDomain;
    }

    public Domain getOpenSearchDomain() {
        return openSearchDomain;
    }
}
