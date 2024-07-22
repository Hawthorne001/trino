/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.iceberg.procedure;

import com.google.common.collect.ImmutableList;
import io.airlift.log.Logger;
import io.trino.filesystem.FileEntry;
import io.trino.filesystem.FileIterator;
import io.trino.filesystem.Location;
import io.trino.filesystem.TrinoFileSystem;
import io.trino.filesystem.TrinoInputFile;
import io.trino.parquet.ParquetDataSource;
import io.trino.parquet.ParquetReaderOptions;
import io.trino.parquet.metadata.ParquetMetadata;
import io.trino.parquet.reader.MetadataReader;
import io.trino.plugin.hive.FileFormatDataSourceStats;
import io.trino.plugin.hive.HiveStorageFormat;
import io.trino.plugin.hive.parquet.TrinoParquetDataSource;
import io.trino.plugin.iceberg.fileio.ForwardingInputFile;
import io.trino.plugin.iceberg.util.OrcMetrics;
import io.trino.plugin.iceberg.util.ParquetUtil;
import io.trino.spi.TrinoException;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.Metrics;
import org.apache.iceberg.MetricsConfig;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.avro.Avro;
import org.apache.iceberg.mapping.MappingUtil;
import org.apache.iceberg.mapping.NameMapping;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.google.common.base.Verify.verify;
import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;

public final class MigrationUtils
{
    private static final Logger log = Logger.get(MigrationUtils.class);

    private static final MetricsConfig METRICS_CONFIG = MetricsConfig.getDefault();

    public enum RecursiveDirectory
    {
        TRUE,
        FALSE,
        FAIL,
        /**/
    }

    private MigrationUtils() {}

    public static List<DataFile> buildDataFiles(
            TrinoFileSystem fileSystem,
            RecursiveDirectory recursive,
            HiveStorageFormat format,
            String location,
            PartitionSpec partitionSpec,
            Optional<StructLike> partition,
            Schema schema)
            throws IOException
    {
        // TODO: Introduce parallelism
        FileIterator files = fileSystem.listFiles(Location.of(location));
        ImmutableList.Builder<DataFile> dataFilesBuilder = ImmutableList.builder();
        while (files.hasNext()) {
            FileEntry file = files.next();
            String fileLocation = file.location().toString();
            String relativePath = fileLocation.substring(location.length());
            if (relativePath.contains("/_") || relativePath.contains("/.")) {
                continue;
            }
            if (recursive == RecursiveDirectory.FALSE && isRecursive(location, fileLocation)) {
                continue;
            }
            if (recursive == RecursiveDirectory.FAIL && isRecursive(location, fileLocation)) {
                throw new TrinoException(NOT_SUPPORTED, "Recursive directory must not exist when recursive_directory argument is 'fail': " + file.location());
            }

            Metrics metrics = loadMetrics(fileSystem.newInputFile(file.location()), format, schema);
            DataFile dataFile = buildDataFile(fileLocation, file.length(), partition, partitionSpec, format.name(), metrics);
            dataFilesBuilder.add(dataFile);
        }
        List<DataFile> dataFiles = dataFilesBuilder.build();
        log.debug("Found %d files in '%s'", dataFiles.size(), location);
        return dataFiles;
    }

    private static boolean isRecursive(String baseLocation, String location)
    {
        verify(location.startsWith(baseLocation), "%s should start with %s", location, baseLocation);
        String suffix = location.substring(baseLocation.length() + 1).replaceFirst("^/+", "");
        return suffix.contains("/");
    }

    public static Metrics loadMetrics(TrinoInputFile file, HiveStorageFormat storageFormat, Schema schema)
    {
        return switch (storageFormat) {
            case ORC -> OrcMetrics.fileMetrics(file, METRICS_CONFIG, schema);
            case PARQUET -> parquetMetrics(file, METRICS_CONFIG, MappingUtil.create(schema));
            case AVRO -> new Metrics(Avro.rowCount(new ForwardingInputFile(file)), null, null, null, null);
            default -> throw new TrinoException(NOT_SUPPORTED, "Unsupported storage format: " + storageFormat);
        };
    }

    private static Metrics parquetMetrics(TrinoInputFile file, MetricsConfig metricsConfig, NameMapping nameMapping)
    {
        try (ParquetDataSource dataSource = new TrinoParquetDataSource(file, new ParquetReaderOptions(), new FileFormatDataSourceStats())) {
            ParquetMetadata metadata = MetadataReader.readFooter(dataSource, Optional.empty());
            return ParquetUtil.footerMetrics(metadata, Stream.empty(), metricsConfig, nameMapping);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to read file footer: " + file.location(), e);
        }
    }

    public static DataFile buildDataFile(String path, long length, Optional<StructLike> partition, PartitionSpec spec, String format, Metrics metrics)
    {
        DataFiles.Builder dataFile = DataFiles.builder(spec)
                .withPath(path)
                .withFormat(format)
                .withFileSizeInBytes(length)
                .withMetrics(metrics);
        partition.ifPresent(dataFile::withPartition);
        return dataFile.build();
    }
}