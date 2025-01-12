---
tags:
  - administration
  - configuration
  - couchbase
---

# Couchbase Database Configuration

> Prerequisite: Know how to use the Janssen CLI in [command-line mode](../config-tools/jans-cli/README.md)


Let's get the couchbase database configuration operations details:

```
jans tui --info DatabaseCouchbaseConfiguration
```

```
Operation ID: get-config-database-couchbase
  Description: Gets list of existing Couchbase configurations.
Operation ID: post-config-database-couchbase
  Description: Adds a new Couchbase configuration.
  Schema: /components/schemas/CouchbaseConfiguration
Operation ID: put-config-database-couchbase
  Description: Updates Couchbase configuration.
  Schema: /components/schemas/CouchbaseConfiguration
Operation ID: get-config-database-couchbase-by-name
  Description: Gets a Couchbase configurations by name.
  url-suffix: name
Operation ID: patch-config-database-couchbase-by-name
  Description: Partially modify an Couchbase configuration.
  url-suffix: name
  Schema: Array of /components/schemas/PatchRequest
Operation ID: delete-config-database-couchbase-by-name
  Description: Deletes a Couchbase configurations by name.
  url-suffix: name
Operation ID: post-config-database-couchbase-test
  Description: Tests a Couchbase configuration.
  Schema: /components/schemas/CouchbaseConfiguration

To get sample schema type jans cli --schema <schma>, for example jans cli --schema /components/schemas/CouchbaseConfiguration

```

## Get Couchbase Database Configuration details

To get the database configuration details, run the following command:

```
jans cli --operation-id get-config-database-couchbase
```

## Adds new Database Configurations

To add a new couchbase database configuration into the janssen server:

```
jans cli --operation-id post-config-database-couchbase --data data.json
```


## Update/Replace old couchbase database configurations

To update or replace an couchbase database configuration:

```
jans cli --operation-id put-config-database-couchbase --data data.json
```


## Gets Couchbase Database Configuration by its name

To get the couchbase database configuration by its configId:

```
jans cli --operation-id get-config-database-couchbase-by-name --url-suffix name:configId-name
```

## Delete Couchbase Database Configuration

You can delete the couchbase database configuration by its name.
The command line is:

```
jans cli --operation-id delete-config-database-couchbase-by-name --url-suffix name:configId-name
```

