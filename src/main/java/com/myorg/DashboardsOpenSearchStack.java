package com.myorg;


import software.amazon.awscdk.core.*;
import software.amazon.awscdk.services.cognito.*;
import software.amazon.awscdk.services.ec2.EbsDeviceVolumeType;
import software.amazon.awscdk.services.ec2.InstanceClass;
import software.amazon.awscdk.services.ec2.InstanceSize;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.opensearchservice.*;

import java.util.Collections;
import java.util.Map;

public class DashboardsOpenSearchStack extends Stack {
    private static String NAME_PREFIX = "WAFD";
    private static String DOMAIN_SETTER = "DomainSetter";
    private static String COGNITO_USER_POOL_NAME = "Users";
    private static String COGNITO_IDENTITY_POOL_NAME = "IdentityPool";
    private static String COGNITO_AUTHENTICATED_IP_ROLE_NAME = "AuthenticatedRole";
    private static String COGNITO_AUTHENTICATED_ROLE_NAME = "AuthenticatedGroup";

    private Domain openSearchDomain;
    private CfnParameter dataNodeEBSVolumeSize;
    private CfnParameter nodeType;
    private CfnParameter openSearchDomainName;
    private CfnParameter userEmail;
    private CfnParameter cognitoDomainName;

    private UserPool userPool;
    private CfnIdentityPool identityPool;
    private Role cognitoUserRole;
    private Role authenticatedUserRole;


    public DashboardsOpenSearchStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public DashboardsOpenSearchStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        createParameters();

        configureCognito();

        deployOpenSearch();

        FirehoseNestedStackProps firehoseNestedStackProps = new FirehoseNestedStackProps(this.openSearchDomain);

        DashboardsFirehoseStack firehoseStack = new DashboardsFirehoseStack(this, "osdfw-firehose-stack", firehoseNestedStackProps);

        DashboardsAppStack dashboardsAppStack = new DashboardsAppStack(this, "osdfw-app-stack", firehoseNestedStackProps);

        CfnOutput output = CfnOutput.Builder.create(this, "osdfw-dash-link")
                .description("Your link to the OpenSearch WAF Dashboard")
                .value("https://" + openSearchDomain.getDomainEndpoint() + "/_dashboards")
                .build();

    }

    private void createParameters() {
        this.dataNodeEBSVolumeSize = CfnParameter.Builder.create(this, "osdfw-os-ebs-size")
                .type("Number")
                .defaultValue("10")
                .description("OpenSearch volume disk size")
                .build();

        this.nodeType = CfnParameter.Builder.create(this, "osdfw-os-node-size")
                .type("String")
                .defaultValue(InstanceType.of(InstanceClass.MEMORY6_GRAVITON, InstanceSize.LARGE).toString())
                //.allowedPattern(".*.search")
                .description("OpenSearch Node type")
                .build();

        this.openSearchDomainName = CfnParameter.Builder.create(this, "osdfw-os-domain-name")
                .type("String")
                .defaultValue("osdfw-domain" + this.getStackId())
                .description("OpenSearch Domain Name")
                .build();

        this.userEmail = CfnParameter.Builder.create(this, "osdfw-dashboards-admin-email")
                .type("String")
                .defaultValue("your@email.com")
                .description("Dashboard user e-mail address")
                .build();

        this.cognitoDomainName = CfnParameter.Builder.create(this, "osdfw-cognito-domain")
                .type("String")
                .defaultValue("osdfw-cognito-domain" + this.getStackId())
                //todo lowercase only allowed
                .description("Name for Cognito Domain")
                .build();
    }

    private void deployOpenSearch() {
        String openSearchInstanceType = nodeType.getValueAsString() + ".search";

        PolicyStatement openSearchPolicy = PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .principals(Collections.singletonList(new ArnPrincipal(this.authenticatedUserRole.getRoleArn())))
                .actions(Collections.singletonList("es:ESHttp*"))
                .resources(Collections.singletonList(
                        Arn.format(
                                ArnComponents.builder()
                                        .service("es")
                                        .resource("domain")
                                        .resourceName(openSearchDomainName.getValueAsString() + "/*").build(),
                                this)))
                .build();

        this.openSearchDomain = Domain.Builder.create(this, "osdfw-os-domain")
                .domainName(openSearchDomainName.getValueAsString())
                .version(EngineVersion.OPENSEARCH_1_0)
                .capacity(CapacityConfig.builder()
                        .masterNodes(0)
                        .dataNodes(1)
                        .warmNodes(0)
                        .dataNodeInstanceType("r6g.large.search") //todo bug? passing param doesn't work
                        .build())
                .ebs(EbsOptions.builder()
                        .enabled(true)
                        .volumeSize(dataNodeEBSVolumeSize.getValueAsNumber())
                        .volumeType(EbsDeviceVolumeType.GP2)
                        .build())
                .automatedSnapshotStartHour(0)
                .cognitoDashboardsAuth(CognitoOptions.builder()
                        .identityPoolId(this.identityPool.getRef())
                        .userPoolId(this.userPool.getUserPoolId())
                        .role(this.cognitoUserRole)
                        .build())
                .accessPolicies(Collections.singletonList(openSearchPolicy))
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();
    }

    public void configureCognito() {
        IManagedPolicy awsOpenSearchCognitoAccessPolicy = ManagedPolicy.fromAwsManagedPolicyName("AmazonOpenSearchServiceCognitoAccess");

        //Create cognito user pool
        this.userPool = UserPool.Builder.create(this, "osdfw-user-pool")
                .userPoolName(Helper.buildName(NAME_PREFIX, COGNITO_USER_POOL_NAME, this.getStackId()))
                .accountRecovery(AccountRecovery.EMAIL_ONLY)
                .standardAttributes(StandardAttributes.builder()
                        .email(StandardAttribute.builder()
                                .required(true)
                                .build())
                        .build())
                .passwordPolicy(PasswordPolicy.builder()
                        .minLength(8)
                        .build())
                .userVerification(UserVerificationConfig.builder()
                        .build())
                .autoVerify(AutoVerifiedAttrs.builder()
                        .email(true)
                        .build())
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        //Set Cognito Domain
        CfnUserPoolDomain.Builder.create(this, DOMAIN_SETTER)
                .domain(cognitoDomainName.getValueAsString())
                .userPoolId(userPool.getUserPoolId())
                .build();

        //Create Identity pool
        this.identityPool = CfnIdentityPool.Builder.create(this, COGNITO_IDENTITY_POOL_NAME)
                .identityPoolName(Helper.buildName(NAME_PREFIX, COGNITO_IDENTITY_POOL_NAME, this.getStackId()))
                .allowUnauthenticatedIdentities(false)
                .build();

        //Build Identity Pool role
        this.authenticatedUserRole = Role.Builder.create(this, COGNITO_AUTHENTICATED_IP_ROLE_NAME)
                .roleName(Helper.buildName(NAME_PREFIX, COGNITO_AUTHENTICATED_IP_ROLE_NAME, this.getStackId()))
                .assumedBy(
                        new WebIdentityPrincipal("cognito-identity.amazonaws.com",
                                Map.of(
                                        "StringEquals", Map.of("cognito-identity.amazonaws.com:aud", identityPool.getRef()),
                                        "ForAnyValue:StringLike", Map.of("cognito-identity.amazonaws.com:amr", "authenticated"))
                        ))
                .managedPolicies(Collections.singletonList(awsOpenSearchCognitoAccessPolicy))
                .build();

        //Attach role to IP
        CfnIdentityPoolRoleAttachment.Builder.create(this, "identityPoolRoleAttachement")
                .identityPoolId(identityPool.getRef())
                .roles(Map.of("authenticated", this.authenticatedUserRole.getRoleArn()))
                .build();

        //Create admin user with a password passed as a parameter to the stack
        CfnUserPoolUser.Builder.create(this, "AdminCognitoUser")
                .userPoolId(userPool.getUserPoolId())
                .forceAliasCreation(true)
                .username(userEmail.getValueAsString())
                .desiredDeliveryMediums(Collections.singletonList("EMAIL"))
                .userAttributes(Collections.singletonList(CfnUserPoolUser.AttributeTypeProperty.builder()
                        .name("email")
                        .value(userEmail.getValueAsString()).build()))
                .build();

        //Lastly attach a role to authenticated users
        this.cognitoUserRole = Role.Builder.create(this, COGNITO_AUTHENTICATED_ROLE_NAME)
                .roleName(Helper.buildName(NAME_PREFIX, COGNITO_AUTHENTICATED_ROLE_NAME, this.getStackId()))
                .description("Role attached to Cognito authenticated users")
                .maxSessionDuration(Duration.hours(2))
                .managedPolicies(Collections.singletonList(awsOpenSearchCognitoAccessPolicy))
                .assumedBy(ServicePrincipal.Builder.create("es.amazonaws.com").build())
                .build();
    }


}
