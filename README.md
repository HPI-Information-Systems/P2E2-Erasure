## General requirements
- Maven (`mvn`)
- a modern `Java` version (e.g. Java 21)
- `config.json` (or supply the path to your `config.json` as command line argument; see below for configuration parameters)

## Sample dataset
- To allow easy testing, we packaged all necessary files for the `Twitter` dataset.
- Please import the four data CSVs (`profile.csv`, `profile_insertiontime.csv`, `posts.csv`, `posts_insertiontime.csv`) into your PostgreSQL database.
- Place the other three CSVs (`rule_twitter.csv`, `schema_twitter.csv`, `derived_twitter.csv`) in the `configPath` or link them directly in your `config.json` (see below).
- If no `config.json` is supplied and the three CSVs are placed in the same folder as the `Erasure.jar`, experiment 1 for the Twitter dataset is run.
- The dataset can be downloaded [here](https://my.hidrive.com/lnk/eshsTVV5I).

## Configuration Parameters Documentation
All configuration parameters are optional. The defaults noted in `ConfigParameter.java` are used if no configuration option is given. 
### Dataset and File Paths
- **`dataset`**: Specifies the dataset to be used.
- **`configPath`**: Path to the configuration folder.
- **`ruleFile`**: Path to the rules file, if the file name is not derivable from dataset.
- **`schemaFile`**: Path to the schema file, if the file name is not derivable from dataset.
- **`derivedFile`**: Path to the file that marks derived data attributes, if the file name is not derivable from dataset.
- All table definition in the **`ruleFile`**, **`schemaFile`**, **`derivedFile`** must contain a schema name.

### Database Connection
- **`connectionUrl`**: The URL for connecting to the PostgreSQL database. Default value is `"jdbc:postgresql://localhost:5432/"`.
- **`database`**: The name of the database to connect to. Default value is `"database"`.
- **`username`**: The username for the database connection. Default value is `"postgres"`.
- **`password`**: The password for the database connection. Default value is `"postgres"`.

### Processing Parameters
- **`numKeys`**: The number of keys to be processed.
- **`batching`**: Boolean flag to enable or disable the batching experiments.
- **`scheduling`**: Boolean flag to enable or disable the scheduling experiments.
- The **`batching`** and **`scheduling`** together control which experiment is executed.
- 
  |                  | **`batching`** | `true`       | `false`      |
  |------------------|----------------|--------------|--------------|
  | **`scheduling`** | --             | --           | --           |
  | `true`           | --             | experiment 5 | experiment 4 |
  | `false`          | --             | experiment 3 | experiment 1 |
- **`averageDependence`**: Boolean flag to enable or disable average dependence calculation, i.e., experiment 2.
- **`batchSizes`**: An array of integers specifying the batch sizes.
- **`isBatchSizeTime`**: Boolean flag to indicate if the batch sizes are interpreted as times.
- **`measureMemory`**: Boolean flag to enable or disable memory measurement.
- **`startSchedule`**: The start time for the schedule.
- **`endSchedule`**: The end time for the schedule.
- **`baseFrequency`**: The base frequency for scheduling.

