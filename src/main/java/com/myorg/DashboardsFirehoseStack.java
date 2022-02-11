package com.myorg;


import software.amazon.awscdk.core.CfnOutput;
import software.amazon.awscdk.core.Construct;
import software.amazon.awscdk.core.NestedStack;
import software.amazon.awscdk.core.RemovalPolicy;
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.kinesisfirehose.CfnDeliveryStream;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.LogStream;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.opensearchservice.Domain;
import software.amazon.awscdk.services.s3.Bucket;

import java.util.List;
import java.util.UUID;

public class DashboardsFirehoseStack extends NestedStack {
    LogGroup cwLogGroup;
    LogStream openSearchStream;
    LogStream s3logStream;
    Role firehoseRole;

    public DashboardsFirehoseStack(final Construct scope, final String id, FirehoseNestedStackProps firehoseNestedStackProps) {
        super(scope, id, firehoseNestedStackProps);

        //todo parametrise
        String wafIndexName = "awswaf";
        String wafIndexTypeName = "waflog";
        int streamBufferSize = 5;
        int streamBufferTimeInterval = 60;


        createLoggingConfiguration();

        Bucket logDeliveryBucket = Bucket.Builder.create(this, "waf-logDeliveryBucket")
                .bucketName("waf-log-delivery-bucket-" + UUID.randomUUID().toString())
                .removalPolicy(RemovalPolicy.RETAIN)
                .build();

        this.firehoseRole = generateFirehoseRole(firehoseNestedStackProps, logDeliveryBucket);

        CfnDeliveryStream.ElasticsearchDestinationConfigurationProperty openSearchDestinationForFirehose = CfnDeliveryStream.ElasticsearchDestinationConfigurationProperty.builder()
                .bufferingHints(CfnDeliveryStream.ElasticsearchBufferingHintsProperty.builder()
                        .intervalInSeconds(streamBufferTimeInterval)
                        .sizeInMBs(streamBufferSize)
                        .build())
                .cloudWatchLoggingOptions(CfnDeliveryStream.CloudWatchLoggingOptionsProperty.builder()
                        .enabled(true)
                        .logGroupName(this.cwLogGroup.getLogGroupName())
                        .logStreamName(this.openSearchStream.getLogStreamName())
                        .build())
                .domainArn(firehoseNestedStackProps.getOpenSearchDomain().getDomainArn())
                .indexName(wafIndexName)
                //.typeName(wafIndexTypeName)
                .indexRotationPeriod("OneDay")
                .retryOptions(CfnDeliveryStream.ElasticsearchRetryOptionsProperty.builder().durationInSeconds(60).build())
                .roleArn(this.firehoseRole.getRoleArn())
                .s3BackupMode("AllDocuments")
                .s3Configuration(CfnDeliveryStream.S3DestinationConfigurationProperty.builder()
                        .bucketArn(logDeliveryBucket.getBucketArn())
                        .bufferingHints(CfnDeliveryStream.BufferingHintsProperty.builder()
                                .intervalInSeconds(streamBufferTimeInterval * 5)
                                .sizeInMBs(streamBufferSize * 10)
                                .build())
                        .compressionFormat("ZIP")
                        .prefix("/log")
                        .roleArn(this.firehoseRole.getRoleArn())
                        .cloudWatchLoggingOptions(CfnDeliveryStream.CloudWatchLoggingOptionsProperty.builder()
                                .enabled(true)
                                .logGroupName(this.cwLogGroup.getLogGroupName())
                                .logStreamName(this.s3logStream.getLogStreamName())
                                .build())
                        .build())
                .build();


        CfnDeliveryStream wafLogsDeliveryStream = CfnDeliveryStream.Builder.create(this, "wafLogsDeliveryStream")
                .deliveryStreamName("waf-logs-delivery-stream") //todo parematerise
                .deliveryStreamType("DirectPut")
                .elasticsearchDestinationConfiguration(openSearchDestinationForFirehose)
                .build();


        CfnOutput output = CfnOutput.Builder.create(this, "var")
                .value(firehoseNestedStackProps.getOpenSearchDomain().getDomainArn())
                .build();

    }

    public void createLoggingConfiguration() {
        this.cwLogGroup = LogGroup.Builder.create(this, "wafLogsDeliveryStreamCW")
                .logGroupName("wafLogsDeliveryStreamCW")
                .removalPolicy(RemovalPolicy.DESTROY)
                .retention(RetentionDays.ONE_MONTH)
                .build();

        this.s3logStream = LogStream.Builder.create(this, "s3LogStream")
                .logGroup(cwLogGroup)
                .logStreamName("s3-delivery-log")
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        this.openSearchStream = LogStream.Builder.create(this, "openSearchLogStream")
                .logGroup(cwLogGroup)
                .logStreamName("openSearch-delivery-log")
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();
    }

    public Role generateFirehoseRole(FirehoseNestedStackProps firehoseNestedStackProps, Bucket logDeliveryBucket) {
        Role firehoseRole = Role.Builder.create(this, "wafLogsDeliveryStreamRole")
                .roleName("waf-dashboard-wafLogsDeliveryStream")
                .description("Role for WAF Dashboards log delivery")
                .assumedBy(new ServicePrincipal("firehose.amazonaws.com"))
                .build();

        ManagedPolicy.Builder.create(this, "firehosePolicy")
                .statements(generatePolicyStatements(firehoseNestedStackProps.getOpenSearchDomain(), logDeliveryBucket))
                .managedPolicyName("waf-dashboards-firehose-policy")
                .roles(List.of(firehoseRole))
                .build();

        return firehoseRole;
    }

    public List<PolicyStatement> generatePolicyStatements(Domain openSearchDomain, Bucket firehoseDeliveryBucket) {
        PolicyStatement s3AccessStatement = PolicyStatement.Builder.create()
                .sid("s3AccessStatement")
                .effect(Effect.ALLOW)
                .actions(List.of(
                        "s3:AbortMultipartUpload",
                        "s3:GetBucketLocation",
                        "s3:GetObject",
                        "s3:ListBucket",
                        "s3:ListBucketMultipartUploads",
                        "s3:PutObject"))
                .resources(List.of(
                        firehoseDeliveryBucket.getBucketArn(),
                        firehoseDeliveryBucket.getBucketArn() + "/*"
                ))
                .build();

        PolicyStatement openSearchPutAccessStatement = PolicyStatement.Builder.create()
                .sid("openSearchPutAccessStatement")
                .effect(Effect.ALLOW)
                .actions(List.of(
                        "es:DescribeElasticsearchDomain",
                        "es:DescribeElasticsearchDomains",
                        "es:DescribeElasticsearchDomainConfig",
                        "es:ESHttpPost",
                        "es:ESHttpPut"))
                .resources(List.of(
                        openSearchDomain.getDomainArn(),
                        openSearchDomain.getDomainArn() + "/*"))
                .build();

        PolicyStatement openSearchMiscGetAccessStatement = PolicyStatement.Builder.create()
                .sid("openSearchMiscGetAccessStatement")
                .effect(Effect.ALLOW)
                .actions(List.of("es:ESHttpGet"))
                .resources(List.of(
                        openSearchDomain.getDomainArn() + "/_all/_settings",
                        openSearchDomain.getDomainArn() + "/_cluster/stats",
                        openSearchDomain.getDomainArn() + "/awswaf/_mapping/%FIREHOSE_POLICY_TEMPLATE_PLACEHOLDER%",
                        openSearchDomain.getDomainArn() + "/_nodes",
                        openSearchDomain.getDomainArn() + "/_nodes/stats",
                        openSearchDomain.getDomainArn() + "/_nodes/*/stats",
                        openSearchDomain.getDomainArn() + "/_stats",
                        openSearchDomain.getDomainArn() + "/awswaf/_stats"))
                .build();

        PolicyStatement cwLogDeliveryAccessStatement = PolicyStatement.Builder.create()
                .sid("cwLogDeliveryAccessStatement")
                .effect(Effect.ALLOW)
                .actions(List.of("logs:PutLogEvents"))
                .resources(List.of(this.cwLogGroup.getLogGroupArn() + ":*"))
                .build();

        PolicyStatement admin = PolicyStatement.Builder.create()
                .sid("adminAS")
                .effect(Effect.ALLOW)
                .actions(List.of("*"))
                .resources(List.of("*"))
                .build();

        return List.of(s3AccessStatement,
                openSearchPutAccessStatement,
                openSearchMiscGetAccessStatement,
                cwLogDeliveryAccessStatement,
                admin);//todo);
    }

}
