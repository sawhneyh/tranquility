# Configuration

All Tranquility APIs need a Druid configuration to get started. The simplest way to create one is using a configuration
file.

## Configuration files

Tranquility can use a JSON or YAML file for configuration. Here's a simple example configuration:

```json
{
   "dataSources" : [
      {
         "spec" : {
            "dataSchema" : {
               "dataSource" : "foo",
               "metricsSpec" : [
                  { "type" : "count", "name" : "count" },
                  { "type" : "doubleSum", "fieldName" : "x", "name" : "x" }
               ],
               "granularitySpec" : {
                  "segmentGranularity" : "hour",
                  "queryGranularity" : "none",
                  "type" : "uniform"
               },
               "parser" : {
                  "type" : "string",
                  "parseSpec" : {
                     "format" : "json",
                     "timestampSpec" : { "column" : "timestamp", "format" : "auto" },
                     "dimensionsSpec" : {
                        "dimensions" : ["dim1", "dim2", "dim3"]
                     }
                  }
               }
            },
            "tuningConfig" : {
               "type" : "realtime",
               "windowPeriod" : "PT10M",
               "intermediatePersistPeriod" : "PT10M",
               "maxRowsInMemory" : "100000"
            }
         },
         "properties" : {
            "task.partitions" : "1",
            "task.replicants" : "1"
         }
      }
   ],
   "properties" : {
      "zookeeper.connect" : "localhost"
   }
}
```

The file has two sections:

1. `dataSources` - dataSource configuration, one entry per dataSource.
2. `properties` - global properties that apply to all dataSources. See "Properties" below for details.

The dataSources key should contain a list of dataSource configurations. Each dataSource configuration has two
sections:

1. `spec` - a [Druid ingestion spec](http://druid.io/docs/latest/ingestion/index.html) with no `ioConfig`. Tranquility
supplies its own firehose and plumber, so an ioConfig is not necessary.
2. `properties` - dataSource properties, which will override any global properties. See "Properties" below for details.

### Properties

Any of these properties can be specified either globally, or per-dataSource.

|Property|Description|Default|
|--------|-----------|-------|
|`druid.discovery.curator.path`|Curator service discovery path. This is assumed to be on the same zookeeper cluster as `zookeeper.connect` refers to.|/druid/discovery|
|`druid.selectors.indexing.serviceName`|The druid.service name of the indexing service Overlord node.|druid/overlord|
|`druidBeam.firehoseBufferSize`|Size of buffer used by firehose to store events.|100000|
|`druidBeam.firehoseChunkSize`|Maximum number of events to send to Druid in one HTTP request.|1000|
|`druidBeam.firehoseGracePeriod`|Druid indexing tasks will shut down this long after the windowPeriod has elapsed.|PT5M|
|`druidBeam.firehoseQuietPeriod`|Wait this long for a task to appear before complaining that it cannot be found.|PT1M|
|`druidBeam.firehoseRetryPeriod`|Retry for this long before complaining that events could not be pushed|PT1M|
|`druidBeam.indexRetryPeriod`|If an indexing service overlord call fails for some apparently-transient reason, retry for this long before giving up.|PT1M|
|`druidBeam.overlordLocator`|Strategy for locating the Druid Overlord. Can be "curator".|curator|
|`druidBeam.overlordPollPeriod`|How often to poll the Overlord for task locations. Only applies if taskLocator is "overlord".|PT20S|
|`druidBeam.randomizeTaskId`|True if we should add a random suffix to Druid task IDs. This is useful for testing.|false|
|`druidBeam.taskLocator`|Strategy for locating Druid tasks. Can be "curator" or "overlord".|curator|
|`serialization.format`|Serialization format for objects sent to Druid. Can be "json" or "smile". Does not apply if you define a custom objectWriter.|json|
|`task.partitions`|Number of Druid partitions to create.|1|
|`task.replicants`|Number of instances of each Druid partition to create. This is the *total* number of instances, so 2 replicants means 2 tasks will be created.|1|
|`task.warmingPeriod`|If nonzero, create Druid tasks early. This can be useful if tasks take a long time to start up in your environment.|PT0M|
|`tranquility.blockOnFull`|Whether "send" will block (true) or throw an exception (false) when called while the outgoing queue is full.|true|
|`tranquility.lingerMillis`|Wait this long for batches to collect more messages (up to maxBatchSize) before sending them. Set to zero to disable waiting. Set to -1 to always wait for complete batches before sending. NOTE: With lingerMillis set to -1, if you really want to send patial batches, use flush otherwise partial batches will never be sent.|0|
|`tranquility.maxBatchSize`|Maximum number of messages to send at once.|2000|
|`tranquility.maxPendingBatches`|Maximum number of batches that may be in flight before we block and wait for one to finish.|5|
|`zookeeper.connect`|ZooKeeper connect string.|none; must be provided|
|`zookeeper.timeout`|ZooKeeper session timeout. ISO8601 duration.|PT20S|
|`zookeeper.path`|ZooKeeper znode to use for Tranquility's internal coordination.|/tranquility/beams|

### Code

You can read Tranquility configuration files and instantiate Tranquility senders using the DruidBeams builders. Scaladoc
for that is available at: http://static.druid.io/tranquility/api/latest/#com.metamx.tranquility.druid.DruidBeams$

Or take a look at some example code:

- [Java example](https://github.com/druid-io/tranquility/blob/master/core/src/test/java/com/metamx/tranquility/example/JavaExample.java)
- [Scala example](https://github.com/druid-io/tranquility/blob/master/core/src/test/scala/com/metamx/tranquility/example/ScalaExample.scala)

Note that by default, the senders you get will accept `java.util.Map<String, Object>`, but you can create a sender
for any custom object type by providing your own Timestamper and ObjectWriter. See the Scaladocs for more details.

### Loading Druid extensions

If your Druid ingestion spec requires extensions to read (perhaps you're using an aggregator that is only available in
an extension, such as `approxHistogramFold`) then you can load them by specifying JVM properties on the command line.
The following properties are respected:

|Property|Description|Default|
|--------|-----------|-------|
|`druid.extensions.directory`|The root extension directory where user can put extensions related files. Druid will load extensions stored under this directory.|`extensions` (This is a relative path to Druid's working directory)|
|`druid.extensions.hadoopDependenciesDir`|The root hadoop dependencies directory where user can put hadoop related dependencies files. Druid will load the dependencies based on the hadoop coordinate specified in the hadoop index task.|`hadoop-dependencies` (This is a relative path to Druid's working directory|
|`druid.extensions.loadList`|A JSON array of extensions to load from extension directories by Druid. If it is not specified, its value will be `null` and Druid will load all the extensions under `druid.extensions.directory`. If its value is empty list `[]`, then no extensions will be loaded at all.|null|
|`druid.extensions.searchCurrentClassloader`|This is a boolean flag that determines if Druid will search the main classloader for extensions.  It defaults to true but can be turned off if you have reason to not automatically add all modules on the classpath.|true|

For example, to load the approximate histogram extension from the `/opt/druid/extensions` directory, provide:

```
-Ddruid.extensions.loadList='["druid-histogram"]' -Ddruid.extensions.directory=/opt/druid/extensions
```
