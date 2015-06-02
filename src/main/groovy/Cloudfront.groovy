import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.internal.StaticCredentialsProvider
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.S3Object
import com.amazonaws.services.s3.model.S3ObjectSummary
import com.googlecode.jcsv.CSVStrategy
import com.googlecode.jcsv.reader.CSVReader
import com.googlecode.jcsv.reader.internal.CSVReaderBuilder
import com.googlecode.jcsv.reader.internal.DefaultCSVEntryParser
import groovy.json.JsonBuilder
import groovy.util.logging.Slf4j
import org.apache.commons.io.IOUtils
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat

@Grapes([
@Grab(group = 'com.amazonaws', module = 'aws-java-sdk', version = '1.3.33'),
@Grab(group = 'com.googlecode.jcsv', module = 'jcsv', version = '1.4.0'),
@Grab(group = 'commons-io', module = 'commons-io', version = '2.4'),
@Grab(group = 'joda-time', module = 'joda-time', version = '2.3')
])
@Slf4j
class Cloudfront {

    def run(config) {
        new JsonBuilder([usage: [value: loadUsage(config), unit: "GB"]]).toPrettyString()
    }

    def s3_buckets(config) {
        AmazonS3Client s3 = new AmazonS3Client(new StaticCredentialsProvider(new BasicAWSCredentials(config.username, config.password)))
        s3.listBuckets().collectEntries {
            ["$it.name": it]
        }
    }

    def auth(config) {
        AmazonS3Client s3 = new AmazonS3Client(new StaticCredentialsProvider(new BasicAWSCredentials(config.username, config.password)))

        if (null == s3.listObjects(config.s3_billing_bucket).getObjectSummaries().find { it.bucketName == config.s3_billing_bucket }) {
            throw new RuntimeException("Unable to find bucket: $config.s3_billing_bucket" as String)
        }

        true
    }

    def loadUsage(config) throws IOException {
        processCSV(extractCSV(config))
    }

    private String extractCSV(config) throws IOException {
        AmazonS3Client s3 = new AmazonS3Client(new StaticCredentialsProvider(new BasicAWSCredentials(config.username, config.password)))

        List<S3ObjectSummary> s3objects = s3.listObjects(config.s3_billing_bucket).getObjectSummaries()
        for (S3ObjectSummary s3ObjectSummary : s3objects) {
            if (s3ObjectSummary.getKey().contains("aws-billing-csv-" + DateTime.now(DateTimeZone.UTC).toString(DateTimeFormat.forPattern("YYYY-MM")))) {
                S3Object s3Object = s3.getObject(s3ObjectSummary.getBucketName(), s3ObjectSummary.getKey())
                s3Object.getObjectContent()
                def sw = new StringWriter()
                IOUtils.copy(s3Object.getObjectContent(), sw, "UTF-8")
                return sw.toString()
            }
        }
        return null
    }

    def List<String[]> loadCsv(String csv) {
        final CSVReader<String[]> reader =
                new CSVReaderBuilder<String[]>(new StringReader(csv))
                        .strategy(CSVStrategy.UK_DEFAULT)
                        .entryParser(new DefaultCSVEntryParser())
                        .build()

        List<String[]> strings
        try {
            strings = reader.readAll()
        } catch (IOException e) {
            throw new IllegalStateException("Could not read from StringReader, wha?")
        }
        return strings
    }

    def processCSV(String csv) {
        def usageBuilder = [:]
        int productIdx = 12
        int usageTypeIdx = 15
        int itemDescriptionIdx = 18
        int metricIdx = 21
        int currencyIdx = 23
        int totalCostIdx = 28

        List<String[]> data = loadCsv(csv)

        for (String[] line : data) {

            if (line.length >= metricIdx) {
                String product = line[productIdx]
                String usageType = line[usageTypeIdx] != "" ? line[usageTypeIdx] : product

                if (line[3] == "PayerLineItem" && product == "AmazonCloudFront") {
                    String itemDescription = line[itemDescriptionIdx]

                    if (usageType.contains("Bytes")) {
                        try {
                            Float qty = Float.parseFloat(line[metricIdx])

                            usageBuilder[usageType] = qty
//                                    [
//                                    "description": itemDescription,
//                                    "qty": qty,
//                                    // "currency": line[currencyIdx],
//                                    // "totalCost": line[totalCostIdx]
//                            ]

                        } catch (NumberFormatException e) {
                            log.error("Unable to parse csv data as Float: " + line[metricIdx])
                        }
                    }

                }

            } else {
                println "Unparseable line: $line"
            }
        }

        String.format("%.2f", usageBuilder.collect { it.value }.sum())
    }


    def recipe_config() {
        [
                name: "Cloudfront",
                description: "Usage metrics",
                run_every: 3600,
                identifier: "x.username",
                feed_types: ["usage"],
                fields:
                        [
                                ["name": "username", "displayName": "Access Key ID", "fieldType": "text"],
                                ["name": "password", "displayName": "Access Key Secret", "fieldType": "text"],
                                ["name": "s3_billing_bucket", "displayName": "S3 Billing bucket id", "fieldType": "text"]
                        ],
                screens:
                        [
                                [
                                        header: "Enter your Credentials",
                                        fields: ["username", "password", "s3_billing_bucket"],
                                        submit: "auth"
                                ]
                        ]
        ]
    }

}
