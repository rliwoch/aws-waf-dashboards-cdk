package com.myorg;


import software.amazon.awscdk.core.Construct;
import software.amazon.awscdk.core.NestedStack;
import software.amazon.awscdk.core.RemovalPolicy;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.kinesisfirehose.CfnDeliveryStream;
import software.amazon.awscdk.services.opensearchservice.Domain;
import software.amazon.awscdk.services.s3.Bucket;

import java.util.List;
import java.util.UUID;

public class DashboardsFirehoseStack extends NestedStack {

    public DashboardsFirehoseStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public DashboardsFirehoseStack(final Construct scope, final String id, final FirehoseNestedStackProps props) {
        super(scope, id, props);

        /*DeliveryStream wafLogsDeliveryStream = DeliveryStream.Builder.create(this, "wafLogsDeliveryStream")
                .deliveryStreamName("waf-logs-delivery-stream") //todo parematerise
                .sourceStream(Stream.Builder)*/

        Bucket logDeliveryBucket = Bucket.Builder.create(this, "waf-logDeliveryBucket")
                .bucketName("waf-log-delivery-bucket-" + UUID.randomUUID().toString())
                .removalPolicy(RemovalPolicy.RETAIN)
                .build();

        Role firehoseRole = Role.Builder.create(this, "\"wafLogsDeliveryStream\"")
                .roleName("waf-dashboard-wafLogsDeliveryStream")
                .description("Role for WAF Dashboards log delivery")
                .assumedBy(new ServicePrincipal("firehose.amazonaws.com"))
                .build();


        CfnDeliveryStream.ElasticsearchDestinationConfigurationProperty opensearchDestinationForFirehose = CfnDeliveryStream.ElasticsearchDestinationConfigurationProperty.builder()
                .bufferingHints(CfnDeliveryStream.ElasticsearchBufferingHintsProperty.builder()
                        .intervalInSeconds(60) //todo parametrise
                        .sizeInMBs(5)//todo parametrise
                        .build())
                .cloudWatchLoggingOptions(CfnDeliveryStream.CloudWatchLoggingOptionsProperty.builder()
                        .enabled(true)
                        .logGroupName("wafLogsDeliveryStream")
                        .logStreamName("opensearch-delivery")
                        .build())
                .domainArn(props.getOpenSearchDomain().getDomainName())
                .indexName("awswaf") //todo parametrise
                .typeName("waflog") //todo parametrise
                .indexRotationPeriod("OneDay")
                .retryOptions(CfnDeliveryStream.ElasticsearchRetryOptionsProperty.builder().durationInSeconds(60).build())
                .roleArn(firehoseRole.getRoleArn())
                .s3BackupMode("AllDocuments")
                .s3Configuration(CfnDeliveryStream.S3DestinationConfigurationProperty.builder()
                        .bucketArn(logDeliveryBucket.getBucketArn())
                        .bufferingHints(CfnDeliveryStream.BufferingHintsProperty.builder()
                                .intervalInSeconds(60)
                                .sizeInMBs(50)
                                .build())
                        .compressionFormat("ZIP")
                        .prefix("log/")
                        .roleArn(firehoseRole.getRoleArn())
                        .cloudWatchLoggingOptions(CfnDeliveryStream.CloudWatchLoggingOptionsProperty.builder()
                                .enabled(true)
                                .logGroupName("wafLogsDeliveryStream")
                                .logStreamName("s3Backup")
                                .build())
                        .build())
                .build();


        CfnDeliveryStream wafLogsDeliveryStream = CfnDeliveryStream.Builder.create(this, "wafLogsDeliveryStream")
                .deliveryStreamName("waf-logs-delivery-stream") //todo parematerise
                .deliveryStreamType("DirectPut")
                .elasticsearchDestinationConfiguration(opensearchDestinationForFirehose)
                .build();


    }

    public List<PolicyStatement> generatePolicyStatements(Domain openSearchDomain, Bucket firehoseDeliveryBucket) {
        PolicyStatement s3AccessStatement = PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of(""))
                .build();
        return null;
    }

}
