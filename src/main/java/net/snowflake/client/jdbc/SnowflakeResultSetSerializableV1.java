/*
 * Copyright (c) 2012-2019 Snowflake Computing Inc. All rights reserved.
 */

package net.snowflake.client.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.snowflake.client.core.ChunkDownloader;
import net.snowflake.client.core.HttpUtil;
import net.snowflake.client.core.MetaDataOfBinds;
import net.snowflake.client.core.OCSPMode;
import net.snowflake.client.core.ObjectMapperFactory;
import net.snowflake.client.core.QueryResultFormat;
import net.snowflake.client.core.ResultUtil;
import net.snowflake.client.core.SFArrowResultSet;
import net.snowflake.client.core.SFBaseResultSet;
import net.snowflake.client.core.SFResultSet;
import net.snowflake.client.core.SFResultSetMetaData;
import net.snowflake.client.core.SFSession;
import net.snowflake.client.core.SFSessionProperty;
import net.snowflake.client.core.SFStatement;
import net.snowflake.client.core.SFStatementType;
import net.snowflake.client.core.SessionUtil;
import net.snowflake.client.jdbc.telemetry.NoOpTelemetryClient;
import net.snowflake.client.jdbc.telemetry.Telemetry;
import net.snowflake.client.log.ArgSupplier;
import net.snowflake.client.log.SFLogger;
import net.snowflake.client.log.SFLoggerFactory;
import net.snowflake.common.core.SFBinaryFormat;
import net.snowflake.common.core.SnowflakeDateTimeFormat;
import org.apache.arrow.memory.RootAllocator;

import java.io.IOException;
import java.io.Serializable;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TimeZone;

import static net.snowflake.client.core.Constants.GB;
import static net.snowflake.client.core.Constants.MB;
import static net.snowflake.client.core.SessionUtil.CLIENT_ENABLE_CONSERVATIVE_MEMORY_USAGE;
import static net.snowflake.client.core.SessionUtil.CLIENT_MEMORY_LIMIT;
import static net.snowflake.client.core.SessionUtil.CLIENT_PREFETCH_THREADS;
import static net.snowflake.client.core.SessionUtil.CLIENT_RESULT_CHUNK_SIZE;
import static net.snowflake.client.core.SessionUtil.DEFAULT_CLIENT_MEMORY_LIMIT;
import static net.snowflake.client.core.SessionUtil.DEFAULT_CLIENT_PREFETCH_THREADS;


/**
 * This object is an intermediate object between result JSON from GS and
 * ResultSet. Originally, it is created from result JSON. And it can
 * also be serializable. Logically, it stands for a part of ResultSet.
 * <p>
 * A typical result JSON data section consists of the content of the first chunk
 * file and file metadata for the rest of chunk files e.g. URL, chunk size, etc.
 * So this object consists of one chunk data and a list of chunk file entries.
 * In actual cases, it may only include chunk data or chunk files entries.
 * <p>
 * This object is serializable, so it can be distributed to other threads or
 * worker nodes for distributed processing.
 */
public class SnowflakeResultSetSerializableV1 implements SnowflakeResultSetSerializable,
                                                         Serializable
{
  private static final long serialVersionUID = 1L;

  static final SFLogger logger = SFLoggerFactory.getLogger(SnowflakeResultSetSerializableV1.class);

  static final ObjectMapper mapper = ObjectMapperFactory.getObjectMapper();
  private static final long LOW_MAX_MEMORY = GB;

  /**
   * An Entity class to represent a chunk file metadata.
   */
  static public class ChunkFileMetadata implements Serializable
  {
    private static final long serialVersionUID = 1L;
    String fileURL;
    int rowCount;
    int compressedByteSize;
    int uncompressedByteSize;

    public ChunkFileMetadata(String fileURL,
                             int rowCount,
                             int compressedByteSize,
                             int uncompressedByteSize)
    {
      this.fileURL = fileURL;
      this.rowCount = rowCount;
      this.compressedByteSize = compressedByteSize;
      this.uncompressedByteSize = uncompressedByteSize;
    }

    public void setFileURL(String fileURL)
    {
      this.fileURL = fileURL;
    }

    public String getFileURL()
    {
      return fileURL;
    }

    public int getRowCount()
    {
      return rowCount;
    }

    public int getCompressedByteSize()
    {
      return compressedByteSize;
    }

    public int getUncompressedByteSize()
    {
      return uncompressedByteSize;
    }

    public String toString()
    {
      StringBuilder builder = new StringBuilder(1024);

      builder.append("RowCount: ").append(rowCount).append(", ");
      builder.append("CompressedSize: ").append(compressedByteSize).append(", ");
      builder.append("UnCompressedSize: ").append(uncompressedByteSize).append(", ");
      builder.append("fileURL: ").append(fileURL);

      return builder.toString();
    }
  }

  // Below fields are for the data fields that this object wraps
  String firstChunkStringData; // For ARROW, it's BASE64-encoded arrow file.
  // For JSON,  it's string data for the json.
  int firstChunkRowCount; // It is only used for JSON format result.
  int chunkFileCount;
  List<ChunkFileMetadata> chunkFileMetadatas = new ArrayList<>();

  // below fields are used for building a ChunkDownloader which
  // uses http client to download chunk files
  boolean useJsonParserV2;
  int resultPrefetchThreads;
  String qrmk;
  Map<String, String> chunkHeadersMap = new HashMap<>();
  // Below fields are from session or statement
  SnowflakeConnectString snowflakeConnectionString;
  OCSPMode ocspMode;
  int networkTimeoutInMilli;
  boolean isResultColumnCaseInsensitive;
  int resultSetType;
  int resultSetConcurrency;
  int resultSetHoldability;

  // Below are some metadata fields parsed from the result JSON node
  String queryId;
  String finalDatabaseName;
  String finalSchemaName;
  String finalRoleName;
  String finalWarehouseName;
  SFStatementType statementType;
  boolean totalRowCountTruncated;
  Map<String, Object> parameters = new HashMap<>();
  int columnCount;
  private List<SnowflakeColumnMetadata> resultColumnMetadata =
      new ArrayList<>();
  long resultVersion;
  int numberOfBinds;
  boolean arrayBindSupported;
  long sendResultTime;
  List<MetaDataOfBinds> metaDataOfBinds = new ArrayList<>();
  QueryResultFormat queryResultFormat;

  // Below fields are transient, they are generated from parameters
  transient TimeZone timeZone;
  transient boolean honorClientTZForTimestampNTZ;
  transient SnowflakeDateTimeFormat timestampNTZFormatter;
  transient SnowflakeDateTimeFormat timestampLTZFormatter;
  transient SnowflakeDateTimeFormat timestampTZFormatter;
  transient SnowflakeDateTimeFormat dateFormatter;
  transient SnowflakeDateTimeFormat timeFormatter;
  transient SFBinaryFormat binaryFormatter;
  transient long memoryLimit;

  // Below fields are transient, they are generated on the fly.
  transient JsonNode firstChunkRowset = null; // only used for JSON result
  transient ChunkDownloader chunkDownloader = null;
  transient RootAllocator rootAllocator = null; // only used for ARROW result
  transient SFResultSetMetaData resultSetMetaData = null;

  /**
   * Default constructor.
   */
  public SnowflakeResultSetSerializableV1()
  {

  }

  /**
   * This is copy constructor.
   * <p>
   * NOTE: The copy is NOT deep copy.
   *
   * @param toCopy the source object to be copied.
   */
  private SnowflakeResultSetSerializableV1(SnowflakeResultSetSerializableV1 toCopy)
  {
    // Below fields are for the data fields that this object wraps
    this.firstChunkStringData = toCopy.firstChunkStringData;
    this.firstChunkRowCount = toCopy.firstChunkRowCount;
    this.chunkFileCount = toCopy.chunkFileCount;
    this.chunkFileMetadatas = toCopy.chunkFileMetadatas;

    // below fields are used for building a ChunkDownloader
    this.useJsonParserV2 = toCopy.useJsonParserV2;
    this.resultPrefetchThreads = toCopy.resultPrefetchThreads;
    this.qrmk = toCopy.qrmk;
    this.chunkHeadersMap = toCopy.chunkHeadersMap;

    // Below fields are from session or statement
    this.snowflakeConnectionString = toCopy.snowflakeConnectionString;
    this.ocspMode = toCopy.ocspMode;
    this.networkTimeoutInMilli = toCopy.networkTimeoutInMilli;
    this.isResultColumnCaseInsensitive = toCopy.isResultColumnCaseInsensitive;
    this.resultSetType = toCopy.resultSetType;
    this.resultSetConcurrency = toCopy.resultSetConcurrency;
    this.resultSetHoldability = toCopy.resultSetHoldability;

    // Below are some metadata fields parsed from the result JSON node
    this.queryId = toCopy.queryId;
    this.finalDatabaseName = toCopy.finalDatabaseName;
    this.finalSchemaName = toCopy.finalSchemaName;
    this.finalRoleName = toCopy.finalRoleName;
    this.finalWarehouseName = toCopy.finalWarehouseName;
    this.statementType = toCopy.statementType;
    this.totalRowCountTruncated = toCopy.totalRowCountTruncated;
    this.parameters = toCopy.parameters;
    this.columnCount = toCopy.columnCount;
    this.resultColumnMetadata = toCopy.resultColumnMetadata;
    this.resultVersion = toCopy.resultVersion;
    this.numberOfBinds = toCopy.numberOfBinds;
    this.arrayBindSupported = toCopy.arrayBindSupported;
    this.sendResultTime = toCopy.sendResultTime;
    this.metaDataOfBinds = toCopy.metaDataOfBinds;
    this.queryResultFormat = toCopy.queryResultFormat;

    // Below fields are transient, they are generated from parameters
    this.timeZone = toCopy.timeZone;
    this.honorClientTZForTimestampNTZ = toCopy.honorClientTZForTimestampNTZ;
    this.timestampNTZFormatter = toCopy.timestampNTZFormatter;
    this.timestampLTZFormatter = toCopy.timestampLTZFormatter;
    this.timestampTZFormatter = toCopy.timestampTZFormatter;
    this.dateFormatter = toCopy.dateFormatter;
    this.timeFormatter = toCopy.timeFormatter;
    this.binaryFormatter = toCopy.binaryFormatter;
    this.memoryLimit = toCopy.memoryLimit;

    // Below fields are transient, they are generated on the fly.
    this.firstChunkRowset = toCopy.firstChunkRowset;
    this.chunkDownloader = toCopy.chunkDownloader;
    this.rootAllocator = toCopy.rootAllocator;
    this.resultSetMetaData = toCopy.resultSetMetaData;
  }

  public void setRootAllocator(RootAllocator rootAllocator)
  {
    this.rootAllocator = rootAllocator;
  }

  public void setQueryResultFormat(QueryResultFormat queryResultFormat)
  {
    this.queryResultFormat = queryResultFormat;
  }

  public void setChunkFileCount(int chunkFileCount)
  {
    this.chunkFileCount = chunkFileCount;
  }

  public void setFristChunkStringData(String firstChunkStringData)
  {
    this.firstChunkStringData = firstChunkStringData;
  }

  public void setChunkDownloader(ChunkDownloader chunkDownloader)
  {
    this.chunkDownloader = chunkDownloader;
  }

  public long getUncompressedDataSize()
  {
    long totalSize = this.firstChunkStringData != null
                     ? this.firstChunkStringData.length() : 0;
    for (ChunkFileMetadata entry : chunkFileMetadatas)
    {
      totalSize += entry.getUncompressedByteSize();
    }
    return totalSize;
  }

  public SFResultSetMetaData getSFResultSetMetaData()
  {
    return resultSetMetaData;
  }

  public int getResultSetType()
  {
    return resultSetType;
  }

  public int getResultSetConcurrency()
  {
    return resultSetConcurrency;
  }

  public int getResultSetHoldability()
  {
    return resultSetHoldability;
  }

  public SnowflakeConnectString getSnowflakeConnectString()
  {
    return snowflakeConnectionString;
  }

  public OCSPMode getOCSPMode()
  {
    return ocspMode;
  }

  public String getQrmk()
  {
    return qrmk;
  }

  public int getNetworkTimeoutInMilli()
  {
    return networkTimeoutInMilli;
  }

  public int getResultPrefetchThreads()
  {
    return resultPrefetchThreads;
  }

  public boolean getUseJsonParserV2()
  {
    return useJsonParserV2;
  }

  public long getMemoryLimit()
  {
    return memoryLimit;
  }

  public Map<String, String> getChunkHeadersMap()
  {
    return chunkHeadersMap;
  }

  public List<ChunkFileMetadata> getChunkFileMetadatas()
  {
    return chunkFileMetadatas;
  }

  public RootAllocator getRootAllocator()
  {
    return rootAllocator;
  }

  public QueryResultFormat getQueryResultFormat()
  {
    return queryResultFormat;
  }

  public int getChunkFileCount()
  {
    return chunkFileCount;
  }

  public boolean isArrayBindSupported()
  {
    return arrayBindSupported;
  }

  public String getQueryId()
  {
    return queryId;
  }

  public String getFinalDatabaseName()
  {
    return finalDatabaseName;
  }

  public String getFinalSchemaName()
  {
    return finalSchemaName;
  }

  public String getFinalRoleName()
  {
    return finalRoleName;
  }

  public String getFinalWarehouseName()
  {
    return finalWarehouseName;
  }

  public SFStatementType getStatementType()
  {
    return statementType;
  }

  public boolean isTotalRowCountTruncated()
  {
    return totalRowCountTruncated;
  }

  public Map<String, Object> getParameters()
  {
    return parameters;
  }

  public int getColumnCount()
  {
    return columnCount;
  }

  public List<SnowflakeColumnMetadata> getResultColumnMetadata()
  {
    return resultColumnMetadata;
  }

  public JsonNode getAndClearFirstChunkRowset()
  {
    JsonNode firstChunkRowset = this.firstChunkRowset;
    this.firstChunkRowset = null;
    return firstChunkRowset;
  }

  public int getFirstChunkRowCount()
  {
    return firstChunkRowCount;
  }

  public long getResultVersion()
  {
    return resultVersion;
  }

  public int getNumberOfBinds()
  {
    return numberOfBinds;
  }

  public ChunkDownloader getChunkDownloader()
  {
    return chunkDownloader;
  }

  public SnowflakeDateTimeFormat getTimestampNTZFormatter()
  {
    return timestampNTZFormatter;
  }

  public SnowflakeDateTimeFormat getTimestampLTZFormatter()
  {
    return timestampLTZFormatter;
  }

  public SnowflakeDateTimeFormat getTimestampTZFormatter()
  {
    return timestampTZFormatter;
  }

  public SnowflakeDateTimeFormat getDateFormatter()
  {
    return dateFormatter;
  }

  public SnowflakeDateTimeFormat getTimeFormatter()
  {
    return timeFormatter;
  }

  public TimeZone getTimeZone()
  {
    return timeZone;
  }

  public boolean isHonorClientTZForTimestampNTZ()
  {
    return honorClientTZForTimestampNTZ;
  }

  public SFBinaryFormat getBinaryFormatter()
  {
    return binaryFormatter;
  }

  public long getSendResultTime()
  {
    return sendResultTime;
  }

  public List<MetaDataOfBinds> getMetaDataOfBinds()
  {
    return metaDataOfBinds;
  }

  public String getFirstChunkStringData()
  {
    return firstChunkStringData;
  }

  /**
   * A factory function to create SnowflakeResultSetSerializable object
   * from result JSON node.
   *
   * @param rootNode    result JSON node received from GS
   * @param sfSession   the Snowflake session
   * @param sfStatement the Snowflake statement
   * @return processed ResultSetSerializable object
   * @throws SnowflakeSQLException if failed to parse the result JSON node
   */
  static public SnowflakeResultSetSerializableV1 create(
      JsonNode rootNode,
      SFSession sfSession,
      SFStatement sfStatement)
  throws SnowflakeSQLException
  {
    SnowflakeResultSetSerializableV1 resultSetSerializable =
        new SnowflakeResultSetSerializableV1();
    logger.debug("Entering create()");

    SnowflakeUtil.checkErrorAndThrowException(rootNode);

    // get the query id
    resultSetSerializable.queryId =
        rootNode.path("data").path("queryId").asText();

    JsonNode databaseNode = rootNode.path("data").path("finalDatabaseName");
    resultSetSerializable.finalDatabaseName =
        databaseNode.isNull() ? null : databaseNode.asText();

    JsonNode schemaNode = rootNode.path("data").path("finalSchemaName");
    resultSetSerializable.finalSchemaName =
        schemaNode.isNull() ? null : schemaNode.asText();

    JsonNode roleNode = rootNode.path("data").path("finalRoleName");
    resultSetSerializable.finalRoleName =
        roleNode.isNull() ? null : roleNode.asText();

    JsonNode warehouseNode = rootNode.path("data").path("finalWarehouseName");
    resultSetSerializable.finalWarehouseName =
        warehouseNode.isNull() ? null : warehouseNode.asText();

    resultSetSerializable.statementType = SFStatementType.lookUpTypeById(
        rootNode.path("data").path("statementTypeId").asLong());

    resultSetSerializable.totalRowCountTruncated
        = rootNode.path("data").path("totalTruncated").asBoolean();

    logger.debug("query id: {}", resultSetSerializable.queryId);

    Optional<QueryResultFormat> queryResultFormat = QueryResultFormat
        .lookupByName(rootNode.path("data").path("queryResultFormat").asText());
    resultSetSerializable.queryResultFormat =
        queryResultFormat.orElse(QueryResultFormat.JSON);

    // extract parameters
    resultSetSerializable.parameters =
        SessionUtil.getCommonParams(rootNode.path("data").path("parameters"));

    // initialize column metadata
    resultSetSerializable.columnCount =
        rootNode.path("data").path("rowtype").size();

    for (int i = 0; i < resultSetSerializable.columnCount; i++)
    {
      JsonNode colNode = rootNode.path("data").path("rowtype").path(i);

      SnowflakeColumnMetadata columnMetadata
          = SnowflakeUtil.extractColumnMetadata(
          colNode, sfSession.isJdbcTreatDecimalAsInt());

      resultSetSerializable.resultColumnMetadata.add(columnMetadata);

      logger.debug("Get column metadata: {}",
                   (ArgSupplier) () -> columnMetadata.toString());
    }

    // process the content of first chunk.
    if (resultSetSerializable.queryResultFormat == QueryResultFormat.ARROW)
    {
      resultSetSerializable.firstChunkStringData =
          rootNode.path("data").path("rowsetBase64").asText();
      resultSetSerializable.rootAllocator =
          new RootAllocator(Long.MAX_VALUE);
    }
    else
    {
      resultSetSerializable.firstChunkRowset =
          rootNode.path("data").path("rowset");

      if (resultSetSerializable.firstChunkRowset == null ||
          resultSetSerializable.firstChunkRowset.isMissingNode())
      {
        resultSetSerializable.firstChunkRowCount = 0;
        resultSetSerializable.firstChunkStringData = null;
      }
      else
      {
        resultSetSerializable.firstChunkRowCount =
            resultSetSerializable.firstChunkRowset.size();
        resultSetSerializable.firstChunkStringData =
            resultSetSerializable.firstChunkRowset.toString();
      }

      logger.debug("First chunk row count: {}",
                   resultSetSerializable.firstChunkRowCount);
    }

    // parse file chunks
    resultSetSerializable.parseChunkFiles(rootNode, sfStatement);

    // result version
    JsonNode versionNode = rootNode.path("data").path("version");

    if (!versionNode.isMissingNode())
    {
      resultSetSerializable.resultVersion = versionNode.longValue();
    }

    // number of binds
    JsonNode numberOfBindsNode = rootNode.path("data").path("numberOfBinds");

    if (!numberOfBindsNode.isMissingNode())
    {
      resultSetSerializable.numberOfBinds = numberOfBindsNode.intValue();
    }

    JsonNode arrayBindSupported = rootNode.path("data")
        .path("arrayBindSupported");
    resultSetSerializable.arrayBindSupported =
        !arrayBindSupported.isMissingNode() && arrayBindSupported.asBoolean();

    // time result sent by GS (epoch time in millis)
    JsonNode sendResultTimeNode = rootNode.path("data").path("sendResultTime");
    if (!sendResultTimeNode.isMissingNode())
    {
      resultSetSerializable.sendResultTime = sendResultTimeNode.longValue();
    }

    logger.debug("result version={}", resultSetSerializable.resultVersion);

    // Bind parameter metadata
    JsonNode bindData = rootNode.path("data").path("metaDataOfBinds");
    if (!bindData.isMissingNode())
    {
      List<MetaDataOfBinds> returnVal = new ArrayList<>();
      for (JsonNode child : bindData)
      {
        int precision = child.path("precision").asInt();
        boolean nullable = child.path("nullable").asBoolean();
        int scale = child.path("scale").asInt();
        int byteLength = child.path("byteLength").asInt();
        int length = child.path("length").asInt();
        String name = child.path("name").asText();
        String type = child.path("type").asText();
        MetaDataOfBinds param =
            new MetaDataOfBinds(precision, nullable, scale, byteLength, length,
                                name, type);
        returnVal.add(param);
      }
      resultSetSerializable.metaDataOfBinds = returnVal;
    }

    // setup fields from sessions.
    resultSetSerializable.ocspMode = sfSession.getOCSPMode();
    resultSetSerializable.snowflakeConnectionString =
        sfSession.getSnowflakeConnectionString();
    resultSetSerializable.networkTimeoutInMilli =
        sfSession.getNetworkTimeoutInMilli();
    resultSetSerializable.isResultColumnCaseInsensitive =
        sfSession.isResultColumnCaseInsensitive();

    // setup transient fields from parameter
    resultSetSerializable.setupFieldsFromParameters();

    // The chunk downloader will start prefetching
    // first few chunk files in background thread(s)
    resultSetSerializable.chunkDownloader =
        (resultSetSerializable.chunkFileCount > 0)
        ? new SnowflakeChunkDownloader(resultSetSerializable)
        : new SnowflakeChunkDownloader.NoOpChunkDownloader();

    // Setup ResultSet metadata
    resultSetSerializable.resultSetMetaData =
        new SFResultSetMetaData(resultSetSerializable.getResultColumnMetadata(),
                                resultSetSerializable.queryId,
                                sfSession,
                                resultSetSerializable.isResultColumnCaseInsensitive,
                                resultSetSerializable.timestampNTZFormatter,
                                resultSetSerializable.timestampLTZFormatter,
                                resultSetSerializable.timestampTZFormatter,
                                resultSetSerializable.dateFormatter,
                                resultSetSerializable.timeFormatter);

    return resultSetSerializable;
  }

  /**
   * Some fields are generated from this.parameters, so generate them from
   * this.parameters instead of serializing them.
   */
  private void setupFieldsFromParameters()
  {
    String sqlTimestampFormat = (String) ResultUtil.effectiveParamValue(
        this.parameters, "TIMESTAMP_OUTPUT_FORMAT");

    // Special handling of specialized formatters, use a helper function
    this.timestampNTZFormatter = ResultUtil.specializedFormatter(
        this.parameters,
        "timestamp_ntz",
        "TIMESTAMP_NTZ_OUTPUT_FORMAT",
        sqlTimestampFormat);

    this.timestampLTZFormatter = ResultUtil.specializedFormatter(
        this.parameters,
        "timestamp_ltz",
        "TIMESTAMP_LTZ_OUTPUT_FORMAT",
        sqlTimestampFormat);

    this.timestampTZFormatter = ResultUtil.specializedFormatter(
        this.parameters,
        "timestamp_tz",
        "TIMESTAMP_TZ_OUTPUT_FORMAT",
        sqlTimestampFormat);

    String sqlDateFormat = (String) ResultUtil.effectiveParamValue(
        this.parameters,
        "DATE_OUTPUT_FORMAT");

    this.dateFormatter = SnowflakeDateTimeFormat.fromSqlFormat(sqlDateFormat);

    logger.debug("sql date format: {}, java date format: {}",
                 sqlDateFormat,
                 (ArgSupplier) () ->
                     this.dateFormatter.toSimpleDateTimePattern());

    String sqlTimeFormat = (String) ResultUtil.effectiveParamValue(
        this.parameters,
        "TIME_OUTPUT_FORMAT");

    this.timeFormatter = SnowflakeDateTimeFormat.fromSqlFormat(sqlTimeFormat);

    logger.debug("sql time format: {}, java time format: {}",
                 sqlTimeFormat,
                 (ArgSupplier) () ->
                     this.timeFormatter.toSimpleDateTimePattern());

    String timeZoneName = (String) ResultUtil.effectiveParamValue(
        this.parameters, "TIMEZONE");
    this.timeZone = TimeZone.getTimeZone(timeZoneName);

    this.honorClientTZForTimestampNTZ =
        (boolean) ResultUtil.effectiveParamValue(
            this.parameters,
            "CLIENT_HONOR_CLIENT_TZ_FOR_TIMESTAMP_NTZ");

    logger.debug("Honoring client TZ for timestamp_ntz? {}",
                 this.honorClientTZForTimestampNTZ);

    String binaryFmt = (String) ResultUtil.effectiveParamValue(
        this.parameters, "BINARY_OUTPUT_FORMAT");
    this.binaryFormatter =
        SFBinaryFormat.getSafeOutputFormat(binaryFmt);
  }

  /**
   * Parse the chunk file nodes from result JSON node
   *
   * @param rootNode    result JSON node received from GS
   * @param sfStatement the snowflake statement
   */
  private void parseChunkFiles(JsonNode rootNode,
                               SFStatement sfStatement)
  {
    JsonNode chunksNode = rootNode.path("data").path("chunks");

    if (!chunksNode.isMissingNode())
    {
      this.chunkFileCount = chunksNode.size();

      // Try to get the Query Result Master Key
      JsonNode qrmkNode = rootNode.path("data").path("qrmk");
      this.qrmk = qrmkNode.isMissingNode() ?
                  null : qrmkNode.textValue();

      // Determine the prefetch thread count and memoryLimit
      if (this.chunkFileCount > 0)
      {
        logger.debug("#chunks={}, initialize chunk downloader",
                     this.chunkFileCount);

        adjustMemorySettings(sfStatement);

        // Parse chunk header
        JsonNode chunkHeaders = rootNode.path("data").path("chunkHeaders");
        if (chunkHeaders != null && !chunkHeaders.isMissingNode())
        {
          Iterator<Map.Entry<String, JsonNode>> chunkHeadersIter =
              chunkHeaders.fields();

          while (chunkHeadersIter.hasNext())
          {
            Map.Entry<String, JsonNode> chunkHeader = chunkHeadersIter.next();

            logger.debug("add header key={}, value={}",
                         chunkHeader.getKey(),
                         chunkHeader.getValue().asText());
            this.chunkHeadersMap.put(chunkHeader.getKey(),
                                     chunkHeader.getValue().asText());
          }
        }

        // parse chunk files metadata e.g. url and row count
        for (int idx = 0; idx < this.chunkFileCount; idx++)
        {
          JsonNode chunkNode = chunksNode.get(idx);
          String url = chunkNode.path("url").asText();
          int rowCount = chunkNode.path("rowCount").asInt();
          int compressedSize = chunkNode.path("compressedSize").asInt();
          int uncompressedSize = chunkNode.path("uncompressedSize").asInt();

          this.chunkFileMetadatas.add(
              new ChunkFileMetadata(url, rowCount, compressedSize,
                                    uncompressedSize));

          logger.debug("add chunk, url={} rowCount={} " +
                       "compressedSize={} uncompressedSize={}",
                       url, rowCount, compressedSize, uncompressedSize);
        }

        /*
         * Should JsonParser be used instead of the original Json deserializer.
         */
        this.useJsonParserV2 =
            this.parameters.get("JDBC_USE_JSON_PARSER") != null &&
            (boolean) this.parameters.get("JDBC_USE_JSON_PARSER");
      }
    }
  }

  private void adjustMemorySettings(
      SFStatement sfStatement)
  {
    this.resultPrefetchThreads = DEFAULT_CLIENT_PREFETCH_THREADS;
    if (this.statementType.isSelect()
        && this.parameters
            .containsKey(CLIENT_ENABLE_CONSERVATIVE_MEMORY_USAGE)
        && (boolean) this.parameters
        .get(CLIENT_ENABLE_CONSERVATIVE_MEMORY_USAGE))
    {
      // use conservative memory settings
      this.resultPrefetchThreads =
          sfStatement.getConservativePrefetchThreads();
      this.memoryLimit = sfStatement.getConservativeMemoryLimit();
      int chunkSize =
          (int) this.parameters.get(CLIENT_RESULT_CHUNK_SIZE);
      logger.debug(
          "enable conservative memory usage with prefetchThreads = {} and memoryLimit = {} and " +
          "resultChunkSize = {}",
          this.resultPrefetchThreads, this.memoryLimit,
          chunkSize);
    }
    else
    {
      // prefetch threads
      if (this.parameters.get(CLIENT_PREFETCH_THREADS) != null)
      {
        this.resultPrefetchThreads =
            (int) this.parameters.get(CLIENT_PREFETCH_THREADS);
      }
      this.memoryLimit = initMemoryLimit(this.parameters);
    }

    long maxChunkSize = (int) this.parameters.get(CLIENT_RESULT_CHUNK_SIZE) * MB;
    if (queryResultFormat == QueryResultFormat.ARROW
        && Runtime.getRuntime().maxMemory() < LOW_MAX_MEMORY
        && memoryLimit * 2 + maxChunkSize > Runtime.getRuntime().maxMemory())
    {
      memoryLimit = Runtime.getRuntime().maxMemory() / 2 - maxChunkSize;
      logger.debug("To avoid OOM for arrow buffer allocation, " +
                   "memoryLimit {} should be less than half of the " +
                   "maxMemory {} + maxChunkSize {}",
                   memoryLimit, Runtime.getRuntime().maxMemory(), maxChunkSize);
    }
  }

  /**
   * Calculate memory limit in bytes
   *
   * @param parameters The parameters for result JSON node
   * @return memory limit in bytes
   */
  private static long initMemoryLimit(Map<String, Object> parameters)
  {
    // default setting
    long memoryLimit = DEFAULT_CLIENT_MEMORY_LIMIT * 1024 * 1024;
    if (parameters.get(CLIENT_MEMORY_LIMIT) != null)
    {
      // use the settings from the customer
      memoryLimit =
          (int) parameters.get(CLIENT_MEMORY_LIMIT) * 1024L * 1024L;
    }

    long maxMemoryToUse = Runtime.getRuntime().maxMemory() * 8 / 10;
    if ((int) parameters.get(CLIENT_MEMORY_LIMIT)
        == DEFAULT_CLIENT_MEMORY_LIMIT)
    {
      // if the memory limit is the default value and best effort memory is enabled
      // set the memory limit to 80% of the maximum as the best effort
      memoryLimit = Math.max(memoryLimit, maxMemoryToUse);
    }

    // always make sure memoryLimit <= 80% of the maximum
    memoryLimit = Math.min(memoryLimit, maxMemoryToUse);

    logger.debug("Set allowed memory usage to {} bytes", memoryLimit);
    return memoryLimit;
  }

  /**
   * Setup all transient fields based on serialized fields and System Runtime.
   *
   * @throws SQLException if fails to setup any transient fields
   */
  private void setupTransientFields()
  throws SQLException
  {
    // Setup transient fields from serialized fields
    setupFieldsFromParameters();

    // Setup memory limitation from parameters and System Runtime.
    this.memoryLimit = initMemoryLimit(this.parameters);

    // Create below transient fields on the fly.
    if (QueryResultFormat.ARROW.equals(this.queryResultFormat))
    {
      this.rootAllocator = new RootAllocator(Long.MAX_VALUE);
      this.firstChunkRowset = null;
    }
    else
    {
      this.rootAllocator = null;
      try
      {
        this.firstChunkRowset = (this.firstChunkStringData != null)
                                ? mapper.readTree(this.firstChunkStringData)
                                : null;
      }
      catch (IOException ex)
      {
        throw new SQLException("The JSON data is invalid. The error is: " +
                               ex.getMessage());
      }
    }

    // Setup ResultSet metadata
    this.resultSetMetaData =
        new SFResultSetMetaData(this.getResultColumnMetadata(),
                                this.queryId,
                                null, // This is session less
                                this.isResultColumnCaseInsensitive,
                                this.timestampNTZFormatter,
                                this.timestampLTZFormatter,
                                this.timestampTZFormatter,
                                this.dateFormatter,
                                this.timeFormatter);

    // Allocate chunk downloader if necessary
    chunkDownloader = (this.chunkFileCount > 0)
                      ? new SnowflakeChunkDownloader(this)
                      : new SnowflakeChunkDownloader.NoOpChunkDownloader();
  }

  /**
   * Split this object into small pieces based on the user specified data size.
   *
   * @param maxSizeInBytes the expected max data size wrapped in the result
   *                       ResultSetSerializables object.
   *                       NOTE: if a result chunk size is greater than this
   *                       value, the ResultSetSerializable object will
   *                       include one result chunk.
   * @return a list of SnowflakeResultSetSerializable
   * @throws SQLException if fails to split objects.
   */
  public List<SnowflakeResultSetSerializable> splitBySize(long maxSizeInBytes)
  throws SQLException
  {
    List<SnowflakeResultSetSerializable> resultSetSerializables =
        new ArrayList<>();

    if (this.chunkFileMetadatas.isEmpty() && this.firstChunkStringData == null)
    {
      throw new SQLException("The Result Set serializable is invalid.");
    }

    // In the beginning, only the first data chunk is included in the result
    // serializable, so the chunk files are removed from the copy.
    // NOTE: make sure to handle the case that the first data chunk doesn't
    // exist.
    SnowflakeResultSetSerializableV1 curResultSetSerializable =
        new SnowflakeResultSetSerializableV1(this);
    curResultSetSerializable.chunkFileMetadatas = new ArrayList<>();
    curResultSetSerializable.chunkFileCount = 0;

    for (int idx = 0; idx < this.chunkFileCount; idx++)
    {
      ChunkFileMetadata curChunkFileMetadata =
          this.getChunkFileMetadatas().get(idx);

      // If the serializable object has reach the max size,
      // save current one and create new one.
      if ((curResultSetSerializable.getUncompressedDataSize() > 0) &&
          (maxSizeInBytes < (curResultSetSerializable.getUncompressedDataSize()
                             + curChunkFileMetadata.getUncompressedByteSize())))
      {
        resultSetSerializables.add(curResultSetSerializable);

        // Create new result serializable and reset it as empty
        curResultSetSerializable =
            new SnowflakeResultSetSerializableV1(this);
        curResultSetSerializable.chunkFileMetadatas = new ArrayList<>();
        curResultSetSerializable.chunkFileCount = 0;
        curResultSetSerializable.firstChunkStringData = null;
        curResultSetSerializable.firstChunkRowCount = 0;
        curResultSetSerializable.firstChunkRowset = null;
      }

      // Append this chunk file to result serializable object
      curResultSetSerializable.getChunkFileMetadatas().add(curChunkFileMetadata);
      curResultSetSerializable.chunkFileCount++;
    }

    // Add the last result serializable object into result.
    resultSetSerializables.add(curResultSetSerializable);

    return resultSetSerializables;
  }

  /**
   * Setup JDBC proxy properties if necessary.
   *
   * @param info proxy server properties.
   */
  private void setupProxyPropertiesIfNecessary(Properties info) throws SnowflakeSQLException
  {
    // Setup proxy properties.
    if (info != null && info.size() > 0 &&
        info.getProperty(SFSessionProperty.USE_PROXY.getPropertyKey()) != null)
    {
      Map<SFSessionProperty, Object> connectionPropertiesMap =
          new HashMap<>(info.size());
      Boolean useProxy =
          Boolean.valueOf(info.getProperty(SFSessionProperty.USE_PROXY.getPropertyKey()));
      if (useProxy)
      {
        connectionPropertiesMap.put(SFSessionProperty.USE_PROXY, true);

        // set up other proxy related values.
        String propValue = null;
        if ((propValue = info.getProperty(
            SFSessionProperty.PROXY_HOST.getPropertyKey())) != null)
        {
          connectionPropertiesMap.put(SFSessionProperty.PROXY_HOST, propValue);
        }
        if ((propValue = info.getProperty(
            SFSessionProperty.PROXY_PORT.getPropertyKey())) != null)
        {
          connectionPropertiesMap.put(SFSessionProperty.PROXY_PORT, propValue);
        }
        if ((propValue = info.getProperty(
            SFSessionProperty.PROXY_USER.getPropertyKey())) != null)
        {
          connectionPropertiesMap.put(SFSessionProperty.PROXY_USER, propValue);
        }
        if ((propValue = info.getProperty(
            SFSessionProperty.PROXY_PASSWORD.getPropertyKey())) != null)
        {
          connectionPropertiesMap.put(SFSessionProperty.PROXY_PASSWORD, propValue);
        }
        if ((propValue = info.getProperty(
            SFSessionProperty.NON_PROXY_HOSTS.getPropertyKey())) != null)
        {
          connectionPropertiesMap.put(SFSessionProperty.NON_PROXY_HOSTS, propValue);
        }

        // Setup proxy properties into HttpUtil static cache
        HttpUtil.configureCustomProxyProperties(connectionPropertiesMap);
      }
    }
  }

  /**
   * Get ResultSet from the ResultSet Serializable object so that the user can
   * access the data. The ResultSet is sessionless.
   *
   * @return a ResultSet which represents for the data wrapped in the object
   */
  public ResultSet getResultSet() throws SQLException
  {
    return getResultSet(null);
  }

  /**
   * Get ResultSet from the ResultSet Serializable object so that the user can
   * access the data.
   *
   * @param info The proxy sever information if proxy is necessary.
   * @return a ResultSet which represents for the data wrapped in the object
   */
  public ResultSet getResultSet(Properties info) throws SQLException
  {
    // Setup proxy info if necessary
    setupProxyPropertiesIfNecessary(info);

    // Setup transient fields
    setupTransientFields();

    // This result set is sessionless, so it doesn't support telemetry.
    Telemetry telemetryClient = new NoOpTelemetryClient();
    // The use case is distributed processing, so sortResult is not necessary.
    boolean sortResult = false;
    // Setup base result set.
    SFBaseResultSet sfBaseResultSet = null;
    switch (getQueryResultFormat())
    {
      case ARROW:
      {
        sfBaseResultSet = new SFArrowResultSet(this, telemetryClient,
                                               sortResult);
        break;
      }
      case JSON:
      {
        sfBaseResultSet = new SFResultSet(this, telemetryClient,
                                          sortResult);
        break;
      }
      default:
        throw new SnowflakeSQLException(ErrorCode.INTERNAL_ERROR,
                                        "Unsupported query result format: " +
                                        getQueryResultFormat().name());
    }

    // Create result set
    SnowflakeResultSetV1 resultSetV1 =
        new SnowflakeResultSetV1(sfBaseResultSet, this);

    return resultSetV1;
  }

  public String toString()
  {
    StringBuilder builder = new StringBuilder(16 * 1024);

    builder.append("hasFirstChunk: ")
        .append(this.firstChunkStringData != null ? true : false)
        .append("\n");

    builder.append("RowCountInFirstChunk: ")
        .append(this.firstChunkRowCount)
        .append("\n");

    builder.append("queryResultFormat: ")
        .append(this.queryResultFormat)
        .append("\n");

    builder.append("chunkFileCount: ")
        .append(this.chunkFileCount)
        .append("\n");

    for (ChunkFileMetadata chunkFileMetadata : chunkFileMetadatas)
    {
      builder.append("\t").append(chunkFileMetadata.toString()).append("\n");
    }

    return builder.toString();
  }
}
