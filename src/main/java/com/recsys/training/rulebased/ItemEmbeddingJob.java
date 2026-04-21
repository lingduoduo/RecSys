package com.recsys.training.rulebased;

import org.apache.spark.ml.feature.Word2Vec;
import org.apache.spark.ml.feature.Word2VecModel;
import org.apache.spark.ml.linalg.Vector;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.api.java.UDF1;
import org.apache.spark.sql.types.DataTypes;

import java.net.URL;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.collect_list;
import static org.apache.spark.sql.functions.expr;
import static org.apache.spark.sql.functions.size;
import static org.apache.spark.sql.functions.struct;
import static org.apache.spark.sql.functions.udf;

public class ItemEmbeddingJob {
    private static final String RATINGS_RESOURCE = "/com/recsys/data/ratings.txt";
    private static final String DEFAULT_OUTPUT_PATH = "output/item_embeddings";
    private static final String DEFAULT_MASTER = "local[*]";

    public static void main(String[] args) {
        JobConfig config = JobConfig.fromArgs(args);

        SparkSession spark = SparkSession.builder()
                .appName("ItemEmbeddingJob")
                .master(config.master())
                .getOrCreate();

        try {
            spark.sparkContext().setLogLevel("WARN");

            Dataset<Row> sequenceDf = buildItemSequences(spark, config.ratingsPath(), config.minRating());
            System.out.println("=== User item sequences ===");
            sequenceDf.show(10, false);

            Word2Vec word2Vec = new Word2Vec()
                    .setInputCol("movieIds")
                    .setOutputCol("embeddings")
                    .setVectorSize(config.vectorSize())
                    .setWindowSize(config.windowSize())
                    .setMinCount(config.minCount())
                    .setMaxIter(config.maxIter())
                    .setStepSize(config.stepSize());

            Word2VecModel model = word2Vec.fit(sequenceDf);

            Dataset<Row> itemEmbeddings = model.getVectors();
            System.out.println("=== Item embeddings ===");
            itemEmbeddings.show(10, false);

            if (config.synonymMovieId() != null) {
                System.out.printf("=== Top similar items for movieId = %s ===%n", config.synonymMovieId());
                model.findSynonyms(config.synonymMovieId(), config.synonymCount()).show(false);
            }

            writeEmbeddings(itemEmbeddings, config.outputPath());
            System.out.printf("Wrote item embeddings to %s%n", config.outputPath());
        } finally {
            spark.stop();
        }
    }

    static Dataset<Row> buildItemSequences(SparkSession spark, String ratingsPath, double minRating) {
        Dataset<Row> ratings = spark.read()
                .format("csv")
                .option("header", "true")
                .load(ratingsPath)
                .select(
                        col("userId"),
                        col("movieId").cast("string").alias("movieId"),
                        col("rating").cast("double").alias("rating"),
                        col("timestamp").cast("long").alias("timestamp")
                );

        Column event = struct(col("timestamp"), col("movieId"));
        return ratings
                .where(col("rating").geq(minRating))
                .groupBy("userId")
                .agg(collect_list(event).alias("events"))
                .select(
                        col("userId"),
                        expr("transform(sort_array(events), x -> x.movieId)").alias("movieIds")
                )
                .where(size(col("movieIds")).gt(0));
    }

    private static void writeEmbeddings(Dataset<Row> itemEmbeddings, String outputPath) {
        UDF1<Vector, String> vectorToString = vector -> Arrays.stream(vector.toArray())
                .mapToObj(value -> String.format(Locale.US, "%.8f", value))
                .collect(Collectors.joining(" "));

        Dataset<Row> output = itemEmbeddings
                .select(
                        col("word").cast("int").alias("movieId"),
                        udf(vectorToString, DataTypes.StringType).apply(col("vector")).alias("vector")
                )
                .orderBy("movieId");

        output.coalesce(1)
                .write()
                .mode("overwrite")
                .option("header", "true")
                .csv(outputPath);
    }

    private record JobConfig(
            String ratingsPath,
            String outputPath,
            String master,
            int vectorSize,
            int windowSize,
            int minCount,
            int maxIter,
            double stepSize,
            double minRating,
            String synonymMovieId,
            int synonymCount
    ) {
        static JobConfig fromArgs(String[] args) {
            String ratingsPath = defaultRatingsPath();
            String outputPath = DEFAULT_OUTPUT_PATH;
            String master = DEFAULT_MASTER;
            int vectorSize = 16;
            int windowSize = 5;
            int minCount = 1;
            int maxIter = 10;
            double stepSize = 0.025;
            double minRating = 3.5;
            String synonymMovieId = "1";
            int synonymCount = 10;

            for (String arg : args) {
                String[] kv = arg.split("=", 2);
                if (kv.length != 2) {
                    throw new IllegalArgumentException("Arguments must use --name=value syntax: " + arg);
                }
                String name = kv[0].replaceFirst("^--", "");
                String value = kv[1];
                switch (name) {
                    case "ratings" -> ratingsPath = value;
                    case "output" -> outputPath = value;
                    case "master" -> master = value;
                    case "vector-size" -> vectorSize = Integer.parseInt(value);
                    case "window-size" -> windowSize = Integer.parseInt(value);
                    case "min-count" -> minCount = Integer.parseInt(value);
                    case "max-iter" -> maxIter = Integer.parseInt(value);
                    case "step-size" -> stepSize = Double.parseDouble(value);
                    case "min-rating" -> minRating = Double.parseDouble(value);
                    case "synonym-movie-id" -> synonymMovieId = value.isBlank() ? null : value;
                    case "synonym-count" -> synonymCount = Integer.parseInt(value);
                    default -> throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }

            return new JobConfig(
                    ratingsPath,
                    outputPath,
                    master,
                    vectorSize,
                    windowSize,
                    minCount,
                    maxIter,
                    stepSize,
                    minRating,
                    synonymMovieId,
                    synonymCount
            );
        }

        private static String defaultRatingsPath() {
            URL resource = ItemEmbeddingJob.class.getResource(RATINGS_RESOURCE);
            if (resource == null) {
                throw new IllegalStateException("Classpath resource not found: " + RATINGS_RESOURCE);
            }
            return resource.getPath();
        }
    }
}
