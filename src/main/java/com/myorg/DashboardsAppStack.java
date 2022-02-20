package com.myorg;

import software.amazon.awscdk.core.CustomResource;
import software.amazon.awscdk.core.Duration;
import software.amazon.awscdk.core.NestedStack;
import software.amazon.awscdk.core.RemovalPolicy;
import software.amazon.awscdk.services.events.EventPattern;
import software.amazon.awscdk.services.events.Rule;
import software.amazon.awscdk.services.events.targets.LambdaFunction;
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.*;
import software.amazon.awscdk.services.s3.Bucket;
import software.constructs.Construct;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public class DashboardsAppStack extends NestedStack {
    public Function dashboardsCustomizerLambda;

    public DashboardsAppStack(Construct scope, String id, FirehoseNestedStackProps props) {
        super(scope, id, props);

        //Code lambdaCodeLocation = Code.fromAsset("assets/os-customizer-lambda.zip");
        Code lambdaCodeLocation = Code.fromBucket(Bucket.fromBucketArn(this, "id", "arn:aws:s3:::aws-waf-dashboard-resources"), "os-customizer-lambda.zip");

        Role customizerRole = createLambdaRole();

        this.dashboardsCustomizerLambda = Function.Builder.create(this, "dashboardsCustomizerLambda")
                .architecture(Architecture.ARM_64)
                .description("AWS WAF Dashboards Solution main function")
                .functionName("dashboardsCustomizerLambda")
                .handler("src/lambda_function.handler")
                .role(customizerRole) //todo
                .code(lambdaCodeLocation)
                .runtime(Runtime.PYTHON_3_9)
                .memorySize(128)
                .timeout(Duration.seconds(160))
                .environment(Map.of(
                        "ES_ENDPOINT", props.getOpenSearchDomain().getDomainEndpoint(),
                        "REGION", this.getRegion(),
                        "ACCOUNT_ID", this.getAccount()
                ))
                .build();

        createCustomizer(dashboardsCustomizerLambda, props);

        Function customizerUpdaterLambda = Function.Builder.create(this, "customizerUpdaterLambda")
                .architecture(Architecture.ARM_64)
                .description("AWS WAF Dashboards Solution updater function")
                .functionName("dashboardsUpdaterLambda")
                .handler("src/lambda_function.update")
                .role(customizerRole) //todo
                .code(lambdaCodeLocation)
                .runtime(Runtime.PYTHON_3_9)
                .memorySize(128)
                .timeout(Duration.seconds(160))
                .environment(Map.of(
                        "ES_ENDPOINT", props.getOpenSearchDomain().getDomainEndpoint(),
                        "REGION", this.getRegion(),
                        "ACCOUNT_ID", this.getAccount()
                ))
                .build();

        List<Rule> eventRules = createEvents(customizerUpdaterLambda);

        for (Rule rule : eventRules) {
            customizerUpdaterLambda.addPermission(
                    rule.getRuleName().toLowerCase(Locale.ROOT),
                    Permission.builder()
                            .action("lambda:InvokeFunction")
                            .principal(new ServicePrincipal("events.amazonaws.com"))
                            .sourceArn(rule.getRuleArn())
                            .build());
        }
    }

    private List<Rule> createEvents(Function targetLambdaFn) {
        Rule newAclsForWafV2 = Rule.Builder.create(this, "newAclsForWafV2")
                .description("AWS WAF Dashboards Solution - detects new WebACLs and rules for WAFv2.")
                .ruleName("awafd-waf2-detect-acls")
                .eventPattern(EventPattern.builder()
                        .source(List.of("aws.wafv2"))
                        .detailType(List.of("AWS API Call via CloudTrail"))
                        .detail(Map.of(
                                "eventSource", List.of("wafv2.amazonaws.com"),
                                "eventName", List.of("CreateWebACL", "CreateRule")
                        ))
                        .build())
                .targets(List.of(LambdaFunction.Builder.create(targetLambdaFn).build()))
                .enabled(true)
                .build();

        Rule newAclsRulesForWafRegional = Rule.Builder.create(this, "newAclsRulesForWafRegional")
                .description("AWS WAF Dashboards Solution - detects new WebACLs and rules for WAF Regional.")
                .ruleName("awafd-waf-detect-acls-rules-regional")
                .eventPattern(EventPattern.builder()
                        .source(List.of("aws.waf-regional"))
                        .detailType(List.of("AWS API Call via CloudTrail"))
                        .detail(Map.of(
                                "eventSource", List.of("waf.amazonaws.com"),
                                "eventName", List.of("CreateWebACL", "CreateRule")
                        ))
                        .build())
                .targets(List.of(LambdaFunction.Builder.create(targetLambdaFn).build()))
                .enabled(true)
                .build();

        Rule newAclsRulesForWafGlobal = Rule.Builder.create(this, "newAclsForWafGlocal")
                .description("AWS WAF Dashboards Solution - detects new WebACLs and rules for WAF Global.")
                .ruleName("awafd-waf-detect-acls-rules-global")
                .eventPattern(EventPattern.builder()
                        .source(List.of("aws.waf"))
                        .detailType(List.of("AWS API Call via CloudTrail"))
                        .detail(Map.of(
                                "eventSource", List.of("waf-regional.amazonaws.com"),
                                "eventName", List.of("CreateWebACL", "CreateRule")
                        ))
                        .build())
                .targets(List.of(LambdaFunction.Builder.create(targetLambdaFn).build()))
                .enabled(true)
                .build();

        return List.of(newAclsRulesForWafRegional, newAclsRulesForWafGlobal, newAclsForWafV2);
    }

    private Role createLambdaRole() {
        //todo too broad
        PolicyStatement policyStatement = PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of(
                        "es:UpdateElasticsearchDomainConfig",
                        "logs:CreateLogGroup",
                        "logs:CreateLogStream",
                        "logs:PutLogEvents",
                        "events:PutRule",
                        "events:DeleteRule",
                        "lambda:AddPermission",
                        "events:PutTargets",
                        "events:RemoveTargets",
                        "lambda:RemovePermission",
                        "iam:PassRole",
                        "waf:ListWebACLs",
                        "waf-regional:ListWebACLs",
                        "waf:ListRules",
                        "waf-regional:ListRules",
                        "wafv2:ListWebACLs",
                        "s3:*"
                ))
                .resources(List.of("*"))
                .build();

        ManagedPolicy policy = ManagedPolicy.Builder.create(this, "awafd-customizer-lambda-policy")
                .managedPolicyName("awafd-customizer-lambda-policy")
                .statements(List.of(policyStatement))
                .build();

        return Role.Builder.create(this, "awd-lambda-role")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .description("AWS WAF Dashboards Lambda role")
                .roleName("awafd-customizer-lambda-role")
                .managedPolicies(List.of(policy))
                .build();

    }

    public void createCustomizer(Function dashboardsCustomizerLambda, FirehoseNestedStackProps props) {
        CustomResource.Builder.create(this, "dashboardsCustomizer")
                .serviceToken(dashboardsCustomizerLambda.getFunctionArn())
                .removalPolicy(RemovalPolicy.DESTROY)
                .properties(Map.of(
                        "StackName", this.getStackName(),
                        "Region", this.getRegion(),
                        "Host", props.getOpenSearchDomain().getDomainEndpoint(),
                        "AccountID", this.getAccount()
                ))
                .build();
    }
}
