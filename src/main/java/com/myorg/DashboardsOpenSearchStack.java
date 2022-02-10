package com.myorg;


import lombok.Data;
import lombok.EqualsAndHashCode;
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

@EqualsAndHashCode(callSuper = false)
@Data
public class DashboardsOpenSearchStack extends Stack {
    private Domain openSearchDomain;
    private CfnParameter dataNodeEBSVolumeSize;
    private CfnParameter nodeType;
    private CfnParameter openSearchDomainName;
    private CfnParameter userEmail;
    private CfnParameter cognitoDomainName;

    public DashboardsOpenSearchStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public DashboardsOpenSearchStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        this.dataNodeEBSVolumeSize = CfnParameter.Builder.create(this, "dataNodeEBSVolumeSize")
                .type("Number")
                .defaultValue("10")
                .description("OpenSearch volume disk size")
                .build();

        this.nodeType = CfnParameter.Builder.create(this, "nodeType")
                .type("String")
                .defaultValue(InstanceType.of(InstanceClass.MEMORY6_GRAVITON, InstanceSize.LARGE).toString())
                //.allowedPattern(".*.search")
                .description("OpenSearch Node type")
                .build();

        this.openSearchDomainName = CfnParameter.Builder.create(this, "openSearchDomainName")
                .type("String")
                .defaultValue("waf-dashboards")
                .description("OpenSearch Domain Name")
                .build();

        this.userEmail = CfnParameter.Builder.create(this, "userEmail")
                .type("String")
                .defaultValue("your@email.com")
                .description("Dashboard user e-mail address")
                .build();

        this.cognitoDomainName = CfnParameter.Builder.create(this, "cognitoDomainName")
                .type("String")
                .defaultValue("os-waf-dashboard-domain")
                //todo lowercase only allowed
                .description("Name for Cognito Domain")
                .build();

        IManagedPolicy awsOpenSearchCognitoAccessPolicy = ManagedPolicy.fromAwsManagedPolicyName("AmazonOpenSearchServiceCognitoAccess");

        UserPool userPool = UserPool.Builder.create(this, "UserPool")
                .userPoolName("WAFKibanaUsers")
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


        CfnUserPoolDomain domainSetter = CfnUserPoolDomain.Builder.create(this, "DomainSetter")
                .domain(cognitoDomainName.getValueAsString())
                .userPoolId(userPool.getUserPoolId())
                .build();

        CfnIdentityPool identityPool = CfnIdentityPool.Builder.create(this, "IdentityPool")
                .identityPoolName("WAFKibanaIdentityPool")
                .allowUnauthenticatedIdentities(false)
                .build();


        Role authenticated = Role.Builder.create(this, "wafDashboardAnonCognitoGroupRole")
                .roleName("wafDashboardAnonCognitoGroupRole")
                .assumedBy(
                        new WebIdentityPrincipal("cognito-identity.amazonaws.com",
                                Map.of(
                                        "StringEquals", Map.of("cognito-identity.amazonaws.com:aud", identityPool.getRef()),
                                        "ForAnyValue:StringLike", Map.of("cognito-identity.amazonaws.com:amr", "authenticated"))
                        ))
                .managedPolicies(Collections.singletonList(awsOpenSearchCognitoAccessPolicy))
                .build();

        CfnIdentityPoolRoleAttachment identityPoolRoleAttachment = CfnIdentityPoolRoleAttachment.Builder.create(this, "identityPoolRoleAttachement")
                .identityPoolId(identityPool.getRef())
                .roles(Map.of("authenticated", authenticated.getRoleArn()))
                .build();

        CfnUserPoolUser adminUser = CfnUserPoolUser.Builder.create(this, "AdminCognitoUser")
                .userPoolId(userPool.getUserPoolId())
                .forceAliasCreation(true)
                .username(userEmail.getValueAsString())
                .desiredDeliveryMediums(Collections.singletonList("EMAIL"))
                .userAttributes(Collections.singletonList(CfnUserPoolUser.AttributeTypeProperty.builder()
                        .name("email")
                        .value(userEmail.getValueAsString()).build()))
                .build();

        Role cognitoConfigRole = Role.Builder.create(this, "cognitoConfigRole")
                .roleName("AuthenticatedRole")
                .description("Role attached to Cognito authenticated users")
                .maxSessionDuration(Duration.hours(2))
                .managedPolicies(Collections.singletonList(awsOpenSearchCognitoAccessPolicy))
                .assumedBy(ServicePrincipal.Builder.create("es.amazonaws.com").build())
                .build();

        String openSearchInstanceType = nodeType.getValueAsString() + ".search";

        PolicyStatement openSearchPolicy = PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .principals(Collections.singletonList(new ArnPrincipal(authenticated.getRoleArn())))
                .actions(Collections.singletonList("es:ESHttp*"))
                .resources(Collections.singletonList(
                        Arn.format(
                                ArnComponents.builder()
                                        .service("es")
                                        .resource("domain")
                                        .resourceName(openSearchDomainName.getValueAsString() + "/*").build(),
                                this)))
                .build();

        this.openSearchDomain = Domain.Builder.create(this, "OpenSearchDomain")
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
                        .identityPoolId(identityPool.getRef())
                        .userPoolId(userPool.getUserPoolId())
                        .role(cognitoConfigRole)
                        .build())
                .accessPolicies(Collections.singletonList(openSearchPolicy))
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();


        CfnOutput output = CfnOutput.Builder.create(this, "DashboardsLink")
                .description("Your link to the OpenSearch WAF Dashboard")
                .value("https://" + openSearchDomain.getDomainEndpoint() + "/_dashboards")
                .build();


    }


}
