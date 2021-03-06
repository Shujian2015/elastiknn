---
layout: default
title: API
nav_order: 2
description: "Elastiknn API"
permalink: /api/
---

# Elastiknn API
{: .no_toc }

This document covers the Elastiknn API, including the REST API payloads and some important implementation details.

Once you've [installed Elastiknn](/installation/), you can use the REST API just like you would use the [official Elasticsearch REST APIs](https://www.elastic.co/guide/en/elasticsearch/reference/current/rest-apis.html).

1. TOC
{:toc}

## Mappings

Before indexing vectors you must define a mapping specifying one of two vector datatypes, an indexing model, and the model's parameters. 
This determines which queries are supported for the indexed vectors.

### General Structure

The general mapping structure looks like this:

```json
PUT /my-index/_mapping
{
  "properties": {                               # 1
    "my_vec": {                                 # 2 
      "type": "elastiknn_sparse_bool_vector",   # 3
      "elastiknn": {                            # 4
        "dims": 100,                            # 5
        "model": "sparse_indexed",              # 6
        ...                                     # 7
      }
    }
  }
}
```

|#|Description|
|:--|:--|
|1|Dictionary of document fields. Same as the [official PUT Mapping API.](https://www.elastic.co/guide/en/elasticsearch/reference/current/indices-put-mapping.html)|
|2|Name of the field containing your vector. This is arbitrary and can be nested under other fields.|
|3|Type of vector you want to store. See datatypes below.|
|4|Dictionary of elastiknn settings.|
|5|Dimensionality of your vector. All vectors stored at this field (`my_vec`) must have the same dimensionality.|
|6|Model type. This and the model parameters will determine what kind of searches you can run. See more on models below.|
|7|Additional model parameters. See models below.|

### elastiknn_sparse_bool_vector Datatype

This type is optimized for vectors where each index is either `true` or `false` and the majority of indices are `false`. 
For example, you might represent a bag-of-words encoding of a document, where each index corresponds to a word in a vocabulary and any single document contains a very small fraction of all words. 
Internally, Elastiknn saves space by only storing the true indices.

```json
PUT /my-index/_mapping
{
    "properties": {
        "my_vec": {
            "type": "elastiknn_sparse_bool_vector",  # 1
            "elastiknn": {
                "dims": 25000,                       # 2
                ...                                  # 3
            }
        }
    }
}
```

|#|Description|
|:--|:--|
|1|Type name.|
|2|Dimensionality of the vector. This is the total number of possible indices.|
|3|Aditional model parameters. See models below.|

### elastiknn_dense_float_vector Datatype

This type is optimized for vectors where each index is a floating point number, all of the indices are populated, and the dimensionality usually doesn't exceed ~1000. 
For example, you might store a word embedding or an image vector. 
Internally, Elastiknn uses Java Floats to store the values.

```json
PUT /my-index/_mapping
{
    "properties": {
        "my_vec": {
            "type": "elastiknn_dense_float_vector",  # 1
            "elastiknn": {
                "dims": 100,                         # 2
                ...                                  # 3
            }
        }
    }
}
```

|#|Description|
|:--|:--|
|1|Type name.|
|2|Dimensionality of the vector. This shouldn't exceed single-digit thousands. If it does, consider doing some sort of dimensionality reduction.|
|3|Aditional model parameters. See models below.|

### Exact Mapping

The exact model will allow you to run exact searches. 
These don't leverage any indexing constructs and have `O(n^2)` runtime, where `n` is the total number of documents.

You don't need to supply any `"model": "..."` value or any model parameters to use this model.

```json
PUT /my-index/_mapping
{
    "properties": {
        "my_vec": {
            "type": "elastiknn_(dense_float | sparse_bool)_vector",  # 1
            "elastiknn": {
                "dims": 100,                                         # 2
            }
        }
    }
}
```

|#|Description|
|:--|:--|
|1|Vector datatype. Both dense float and sparse bool are supported|
|2|Vector dimensionality.|

### Sparse Indexed Mapping

The sparse indexed model introduces an obvious optimization for exact queries on sparse bool vectors. 
It indexes each of the true indices as a Lucene term, basically treating them like [Elasticsearch keywords](https://www.elastic.co/guide/en/elasticsearch/reference/current/keyword.html). Jaccard and Hamming similarity both require computing the intersection of the query vector against all indexed vectors, and indexing the true indices makes this operation much more efficient. However, you must consider that there is an upper bound on the number of possible terms in a term query, [see the `index.max_terms_count` setting.](https://www.elastic.co/guide/en/elasticsearch/reference/current/index-modules.html#index-max-terms-count) 
If the number of true indices in your vectors exceeds this limit, you'll have to adjust it or you'll encounter failed queries.

```json
PUT /my-index/_mapping
{
    "properties": {
        "my_vec": {
            "type": "elastiknn_sparse_bool_vector",  # 1
            "elastiknn": {
                "dims": 25000,                       # 2
                "model": "sparse_indexed",           # 3
            }
        }
    }
}
```

|#|Description|
|:--|:--|
|1|Vector datatype. Must be sparse bool vector.|
|2|Vector dimensionality.|
|3|Model type. This model has no additional parameters.|

### Jaccard LSH Mapping

Uses the [Minhash algorithm](https://en.wikipedia.org/wiki/MinHash) to hash and store sparse bool vectors such that they
support approximate Jaccard similarity queries.

The implementation is influenced by Chapter 3 of [Mining Massive Datasets.](http://www.mmds.org/), 
the [Spark MinHash implementation](https://spark.apache.org/docs/2.2.3/ml-features.html#minhash-for-jaccard-distance), 
the [tdebatty/java-LSH Github project](https://github.com/tdebatty/java-LSH), 
and the [Minhash for Dummies](http://matthewcasperson.blogspot.com/2013/11/minhash-for-dummies.html) blog post.

```json
PUT /my-index/_mapping
{
    "properties": {
        "my_vec": {
            "type": "elastiknn_sparse_bool_vector", # 1
            "elastiknn": {
                "dims": 25000,                      # 2
                "model": "lsh",                     # 3
                "similarity": "jaccard",            # 4
                "L": 99,                            # 5
                "k": 1                              # 6
            }
        }
    }
}
```

|#|Description|
|:--|:--|
|1|Vector datatype. Must be sparse bool vector.|
|2|Vector dimensionality.|
|3|Model type.|
|4|Similarity.|
|5|Number of hash tables. Generally, increasing this value increases recall.|
|6|Number of hash functions combined to form a single hash value. Generally, increasing this value increases precision.|

### Hamming LSH Mapping

Uses the [Bit-Sampling algorithm](http://mlwiki.org/index.php/Bit_Sampling_LSH) to hash and store sparse bool vectors
such that they support approximate Hamming similarity queries.

Only difference from the canonical bit-sampling method is that it samples and combines `k` bits to form a single hash value.
For example, if you set `L = 100, k = 3`, it samples `100 * 3 = 300` bits from the vector and concatenates sets of 3 
bits to form each hash value, for a total of 100 hash values.

```json
PUT /my-index/_mapping
{
    "properties": {
        "my_vec": {
            "type": "elastiknn_sparse_bool_vector", # 1
            "elastiknn": {
                "dims": 25000,                      # 2
                "model": "lsh",                     # 3
                "similarity": "hamming",            # 4
                "L": 99,                            # 5
                "k": 2
            }
        }
    }
}
```

|#|Description|
|:--|:--|
|1|Vector datatype. Must be sparse bool vector.|
|2|Vector dimensionality.|
|3|Model type.|
|4|Similarity.|
|5|Number of hash tables. Generally, increasing this value increases recall.|
|6|Number of hash functions combined to form a single hash value. Generally, increasing this value increases precision.|

### Angular LSH Mapping

Uses the [Random Projection algorithm](https://en.wikipedia.org/wiki/Locality-sensitive_hashing#Random_projection)
to hash and store dense float vectors such that they support approximate Angular similarity queries.

The implementation is influenced by Chapter 3 of [Mining Massive Datasets.](http://www.mmds.org/)

```json
PUT /my-index/_mapping
{
    "properties": {
        "my_vec": {
            "type": "elastiknn_dense_float_vector", # 1
            "elastiknn": {
                "dims": 100,                        # 2
                "model": "lsh",                     # 3
                "similarity": "angular",            # 4
                "L": 99,                            # 5
                "k": 1                              # 6
            }
        }
    }
}
```

|#|Description|
|:--|:--|
|1|Vector datatype. Must be dense float vector.|
|2|Vector dimensionality.|
|3|Model type.|
|4|Similarity.|
|5|Number of hash tables. Generally, increasing this value increases recall.|
|6|Number of hash functions combined to form a single hash value. Generally, increasing this value increases precision.|

### L2 LSH Mapping

Uses the [Stable Distributions method](https://en.wikipedia.org/wiki/Locality-sensitive_hashing#Stable_distributions)
to hash and store dense float vectors such that they support approximate L2 (Euclidean) similarity queries.

The implementation is influenced by Chapter 3 of [Mining Massive Datasets.](http://www.mmds.org/)

```json
PUT /my-index/_mapping
{
    "properties": {
        "my_vec": {
            "type": "elastiknn_dense_float_vector", # 1
            "elastiknn": {
                "dims": 100,                        # 2
                "model": "lsh",                     # 3
                "similarity": "l2",                 # 4
                "L": 99,                            # 5
                "k": 1,                             # 6
                "r": 3                              # 7
            }
        }
    }
}
```

|#|Description|
|:--|:--|
|1|Vector datatype. Must be dense float vector.|
|2|Vector dimensionality.|
|3|Model type.|
|4|Similarity.|
|5|Number of hash tables. Generally, increasing this value increases recall.|
|6|Number of hash functions combined to form a single hash value. Generally, increasing this value increases precision.|
|7|Integer bucket width. This determines how close two vectors have to be, when projected onto a third common vector, in order for the two vectors to share a hash value. Typical values are low single-digit integers.|

### Permutation LSH Mapping

Uses the model described in [Large-Scale Image Retrieval with Elasticsearch by Amato, et. al.](https://dl.acm.org/doi/10.1145/3209978.3210089).

This model describes a vector by the `k` indices (_positions in the vector_) with the greatest absolute values.
The intuition is that each index corresponds to some latent concept, and indices with high absolute values carry more 
information about their respective concepts than those with low absolute values.
The research for this method has focused mainly on Angular similarity, though the implementation supports Angular, L1, and L2.

**An example**

The vector `[10, -2, 0, 99, 0.1, -8, 42, -13, 6, 0.1]` with `k = 4` is represented by indices `[4, 7, -8, 1]`.
Indices are 1-indexed and indices for negative values are negated (hence the -8). 
Indices can optionally be repeated based on their ranking.
In this example, the indices would be repeated `[4, 4, 4, 4, 7, 7, 7, -8, -8, 1]`.
Index 4 has the highest absolute value, so it's repeated `k - 0 = 4` times. 
Index 7 has the second highest absolute value, so it's repeated `k - 1 = 3` times, and so on.
The search algorithm computes the score as the size of the intersection of the stored vector's representation and the 
query vector's representation.
So for a query vector represented by `[2, 2, 2, 2, 7, 7, 7, 4, 4, 5]`, the intersection is `[7, 7, 7, 4, 4]`, producing
a score of 5. 
In some experiments, repetition has actually decreased recall, so it's advised that you try with and without repetition.

```json
PUT /my-index/_mapping
{
    "properties": {
        "my_vec": {
            "type": "elastiknn_dense_float_vector", # 1
            "elastiknn": {
                "dims": 100,                        # 2
                "model": "permutation_lsh",         # 3
                "similarity": "angular",            # 4
                "k": 10,                            # 5
                "repeating": true                   # 6
            }
        }
    }
}
```

|#|Description|
|:--|:--|
|1|Vector datatype. Must be dense float vector.|
|2|Vector dimensionality.|
|3|Model type.|
|4|Similarity. Supports angular, l1, and l2|
|5|The number of top indices to pick.|
|6|Whether or not to repeat the indices proportionally to their rank. See the notes on repeating above.|
  

## Vectors

You need to specify vectors in your REST requests when indexing documents containing a vector and when running queries
with a literal query vector. 
In both cases you use the same JSON structure to define vectors. 
The examples below show the indexing case; the query case will be covered later.

### elastiknn_sparse_bool_vector

This assumes you've defined a mapping where `my_vec` has type `elastiknn_sparse_bool_vector`.

```json
POST /my-index/_doc
{
    "my_vec": {
       "true_indices": [1, 3, 5, ...],   # 1
       "total_indices": 100,             # 2
    }
}

```

|#|Description|
|:--|:--|
|1|JSON list of the indices which are `true` in your vector.|
|2|The total number of indices in your vector. This should match the `dims` in your mapping.|

### elastiknn_dense_float_vector

This assumes you've defined a mapping where `my_vec` has type `elastiknn_dense_float_vector`.

```json
POST /my-index/_doc
{
    "my_vec": {
        "values": [0.1, 0.2, 0.3, ...]    # 1
    }
}
```

|#|Description|
|:--|:--|
|1|JSON list of all floating point values in your vector. The length should match the `dims` in your mapping.|

## Nearest Neighbor Queries

Elastiknn provides a query called `elastiknn_nearest_neighbors`, which can be used in a `GET /_search` request just like 
standard Elasticsearch queries, as well as in combination with standard Elasticsearch queries. 

### General Structure

The general query structure looks like this:

```json
GET /my-index/_search
{
    "query": {
        "elastiknn_nearest_neighbors": {        # 1
            "field": "my_vec",                  # 2
            "vec": {                            # 3
                "values": [0.1, 0.2, 0.3, ...],               
            },
            "model": "exact",                   # 4
            "similarity": "angular",            # 5
            ...                                 # 6
        }
    }
}
```

|#|Description|
|:--|:--|
|1|Query type provided by Elastiknn.|
|2|Name of the field containing your vectors.|
|3|Query vector. In this example it's a literal vector, but can also be a reference to an indexed vector.|
|4|Model type. Exact search used in this example. More models covered below.|
|5|One of the five similarity functions used to score indexed vectors.|
|6|Additional query parameters for different model/similarity combinations. Covered more below.|

### Compatibility of Vector Types and Similarities

Jaccard and Hamming similarity only work with sparse bool vectors. 
Angular, L1, and L2 similarity only work with dense float vectors. 
The following documentation assume this restriction is known.

These restrictions aren't inherent to the types and algorithms, i.e., you could in theory run angular similarity on sparse vectors.
The restriction merely reflects the most common patterns and simplifies the implementation.

### Similarity Scoring

Elasticsearch queries must return a non-negative floating-point score. 
For Elastiknn, the score for an indexed vector represents its similarity to the query vector. 
However, not all similarity functions increase as similarity increases. 
For example, a perfect similarity for the L1 and L2 functions is 0. 
Such functions really represent _distance_ without a well-defined mapping from distance to similarity. 
In these cases Elastiknn applies a transformation to invert the score such that more similar vectors have higher scores. 
The exact transformations are described below.

|Similarity|Transformtion to Elasticsearch Score|Min Value|Max Value|
|:--|:--|:--|
|Jaccard|N/A|0|1.0|
|Hamming|N/A|0|1.0|
|Angular|`cosine similarity + 1`|0|2|
|L1|`1 / (1 + l1 distance)`|0|1|
|L2|`1 / (1 + l2 distance)`|0|1|

If you're using the `elastiknn_nearest_neighbors` query with other queries, and the score values are inconvenient (e.g. huge values like 1e6), consider wrapping the query in a [Script Score Query](https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl-script-score-query.html), where you can access and transform the `_score` value.

### Query Vector

The query vector is either a literal vector or a pointer to an indexed vector.

```json
GET /my-index/_search
{
    "query": {
        "elastiknn_nearest_neighbors": {    
            ...
            "vec": {                                # 1
                "true_indices": [1, 3, 5, ...],
                "total_indices": 1000
            },
            ...
            "vec": {                                # 2
                "values": [0.1, 0.2, 0.3, ...]
            },
            ...
            "vec": {                                # 3
                "index": "my-other-index",
                "field": "my_vec",
                "id": "abc123"
            },
        }
    }
}
```

|#|Description|
|:--|:--|
|1|Literal sparse bool query vector.|
|2|Literal dense float query vector.|
|3|Indexed query vector. This assumes you have another index called `my-other-index` with a document with id `abc123` that contains a valid vector in field `my_vec`.|

### Exact Query

Computes the exact similarity of a query vector against all indexed vectors. The algorithm is not efficient compared to approximate search, but the implementation has been extensively profiled and optimized.

```json
GET /my-index/_search
{
    "query": {
        "elastiknn_nearest_neighbors": {        
            "field": "my_vec", 
            "vec": {                                # 1
                "values": [0.1, 0.2, 0.3, ...],
            },
            "model": "exact",                       # 2
            "similarity": "(angular | l1 | l2)",    # 3
        }
    }
}
```

|#|Description|
|:--|:--|
|1|Query vector. Must match the datatype of `my_vec` or be a pointer to an indexed vector that matches the type.|
|2|Model name.|
|3|Similarity function. Must be compatible with the vector type.|

### Sparse Indexed Query

Computes the exact similarity of sparse bool vectors using a Lucene Boolean Query to compute the size of the intersection of true indices in the query vector against true indices in the indexed vectors.

```json
GET /my-index/_search
{
    "query": {
        "elastiknn_nearest_neighbors": {        
            "field": "my_vec",                      # 1
            "vec": {                                # 2
                "true_indices": [1, 3, 5, ...],
                "total_indices": 100
            },
            "model": "sparse_indexed",              # 3
            "similarity": "(jaccard | hamming)",    # 4
        }
    }
}
```

|#|Description|
|:--|:--|
|1|Indexed field. Must use `sparse_indexed` mapping model.|
|2|Query vector. Must be literal sparse bool or a pointer to an indexed sparse bool vector.|
|3|Model name.|
|4|Similarity function. Must be jaccard or hamming.|

### LSH Search Strategy

All LSH search models follow roughly the same strategy. 
They first retrieve approximate neighbors based on common hash terms and then compute the exact similarity for a subset of the best approximate candidates. 
The exact steps are as follows:

1. Hash the query vector using model parameters that were specified in the indexed vector's mapping.
2. Use the hash values to construct and execute a query that finds other vectors with the same hash values.
   The query is a modification of Lucene's [TermInSetQuery](https://lucene.apache.org/core/8_5_0/core/org/apache/lucene/search/TermInSetQuery.html).
3. Take the top vectors with the most matching hashes and compute their exact similarity to the query vector.
   The `candidates` parameter controls the number of exact similarity computations.
   Specifically, we compute exact similarity for the top _`candidates`_ candidate vectors in each segment.
   As a reminder, each Elasticsearch index has >= 1 shards, and each shard has >= 1 segments.
   That means if you set `"candiates": 200` for an index with 2 shards, each with 3 segments, then you'll compute the 
   exact similarity for `2 * 3 * 200 = 1200` vectors.
   `candidates` must be set to a number greater or equal to the number of Elasticsearch results you want to get.
   Higher values generally mean higher recall and higher latency.

### Jaccard LSH Query

Retrieve sparse bool vectors based on approximate Jaccard similarity.

```json
GET /my-index/_search
{
    "query": {
        "elastiknn_nearest_neighbors": {
            "field": "my_vec",                     # 1
            "vec": {                               # 2
                "true_indices": [1, 3, 5, ...],
                "total_indices": 100
            },
            "model": "lsh",                        # 3
            "similarity": "jaccard",               # 4
            "candidates": 50                       # 5
        }
    }
}
```

|#|Description|
|:--|:--|
|1|Indexed field. Must use `lsh` mapping model with `jaccard` similarity.|
|2|Query vector. Must be literal sparse bool or a pointer to an indexed sparse bool vector.|
|3|Model name.|
|4|Similarity function.|
|5|Number of candidates per segment. See the section on LSH Search Strategy.|

### Hamming LSH Query

Retrieve sparse bool vectors based on approximate Hamming similarity.

```json
GET /my-index/_search
{
    "query": {
        "elastiknn_nearest_neighbors": {
            "field": "my_vec",                     # 1
            "vec": {                               # 2
                "true_indices": [1, 3, 5, ...],
                "total_indices": 100
            },
            "model": "lsh",                        # 3
            "similarity": "hamming",               # 4
            "candidates": 50                       # 5
        }
    }
}
```

|#|Description|
|:--|:--|
|1|Indexed field. Must use `lsh` mapping model with `hamming` similarity.|
|2|Query vector. Must be literal sparse bool or a pointer to an indexed sparse bool vector.|
|3|Model name.|
|4|Similarity function.|
|5|Number of candidates per segment. See the section on LSH Search Strategy.|
|6|Set to true to use the more-like-this heuristic to pick a subset of hashes. Generally faster but still experimental.|

### Angular LSH Query

Retrieve dense float vectors based on approximate Angular similarity.

```json
GET /my-index/_search
{
    "query": {
        "elastiknn_nearest_neighbors": {
            "field": "my_vec",                     # 1
            "vec": {                               # 2
                "values": [0.1, 0.2, 0.3, ...]
            },
            "model": "lsh",                        # 3
            "similarity": "angular",               # 4
            "candidates": 50                       # 5
        }
    }
}
```

|#|Description|
|:--|:--|
|1|Indexed field. Must use `lsh` mapping model with `angular` similarity.|
|2|Query vector. Must be literal dense float or a pointer to an indexed dense float vector.|
|3|Model name.|
|4|Similarity function.|
|5|Number of candidates per segment. See the section on LSH Search Strategy.|
|6|Set to true to use the more-like-this heuristic to pick a subset of hashes. Generally faster but still experimental.|

### L1 LSH Query

Not yet implemented.

### L2 LSH Query

Retrieve dense float vectors based on approximate L2 similarity.

```json
GET /my-index/_search
{
    "query": {
        "elastiknn_nearest_neighbors": {
            "field": "my_vec",                     # 1
            "vec": {                               # 2
                "values": [0.1, 0.2, 0.3, ...]
            },
            "model": "lsh",                        # 3
            "similarity": "l2",                    # 4
            "candidates": 50                       # 5
        }
    }
}
```

|#|Description|
|:--|:--|
|1|Indexed field. Must use `lsh` mapping model with `l2` similarity.|
|2|Query vector. Must be literal dense float or a pointer to an indexed dense float vector.|
|3|Model name.|
|4|Similarity function.|
|5|Number of candidates per segment. See the section on LSH Search Strategy.|
|6|Set to true to use the more-like-this heuristic to pick a subset of hashes. Generally faster but still experimental.|

### Permutation LSH Query

Retrieve dense float vectors based on the permutation LSH algorithm.
See the permutation LSH mapping for more about the algorithm.

```json
GET /my-index/_search
{
    "query": {
        "elastiknn_nearest_neighbors": {
            "field": "my_vec",                     # 1
            "vec": {                               # 2
                "values": [0.1, 0.2, 0.3, ...]
            },
            "model": "permutation_lsh",            # 3
            "similarity": "angular",               # 4
            "candidates": 50                       # 5
        }
    }
}
```

|#|Description|
|:--|:--|
|1|Indexed field. Must use `permutation_lsh` mapping to use this query.|
|2|Query vector. Must be literal dense float or a pointer to an indexed dense float vector.|
|3|Model name.|
|4|Similarity function. Supports Angular, L1, and L2.|
|5|Number of candidates per segment. See the section on LSH Search Strategy.|

### Model and Query Compatibility

Some models can support more than one type of query. 
For example, sparse bool vectors indexed with the Jaccard LSH model support exact searches using both Jaccard and Hamming similarity. 
The opposite is _not_ true: vectors stored using the exact model do not support Jaccard LSH queries.

The tables below shows valid model/query combinations. 
Rows are models and columns are queries. 
The similarity functions are abbreviated (J: Jaccard, H: Hamming, A: Angular, L1, L2).

#### elastiknn_sparse_bool_vector

|Model / Query                  |Exact    |Sparse Indexed |Jaccard LSH |Hamming LSH |
|:--                            |:--      |:--            |:--         |:--         |
|Exact (i.e. no model specified)|✔ (J, H) |x              |x           |x           |
|Sparse Indexed                 |✔ (J, H) |✔ (J, H)       |x           |x           |
|Jaccard LSH                    |✔ (J, H) |x              |✔           |x           |
|Hamming LSH                    |✔ (J, H) |x              |x           |✔           |

#### elastiknn_dense_float_vector

|Model / Query                   |Exact         |Angular LSH |L2 LSH |Permutation LSH|
|:--                             |:--           |:--         |:--    |:--            |
|Exact (i.e. no model specified) |✔ (A, L1, L2) |x           |x      |x              | 
|Angular LSH                     |✔ (A, L1, L2) |✔           |x      |x              |
|L2 LSH                          |✔ (A, L1, L2) |x           |✔      |x              |
|Permutation LSH                 |✔ (A, L1, L2) |x           |x      |✔              |

### Running Nearest Neighbors Query on a Filtered Subset of Documents

It's common to filter for a subset of documents based on some property and _then_ run the `elastiknn_nearest_neighbors`
query on that subset.
For example, if your docs contain a `color` keyword, you might want to find all of the docs with `"color": "blue"`,
and only run `elastiknn_nearest_neighbors` on that subset.
To do this, you can use the `elastiknn_nearest_neighbors` query in a [boolean query](https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl-bool-query.html).

Consider this example:

```json
GET /my-index/_search
{
    "query": {
        "bool": {
            "filter": [
                { "term": { "color": "blue" } },             # 1
            ],
            "must": {
                "elastiknn_nearest_neighbors": {             # 2
                    "field": "vec",
                    "vec": { 
                        "values": [0.1, 0.2, 0.3, ...]
                    },
                    "model": "exact",
                    "similarity": "l2"
                 }
            }           
        }
    }
}
``` 

|#|Description|
|:--|:--|
|1|Filter clause that will limit the query to only run on documents containing `"color": "blue"`.|
|2|`elastiknn_nearest_neighbors` query that evaluates L2 similarity for the "vec" field in any document containing `"color": "blue"`.|

## Miscellaneous Implementation Details

Here are some other things worth knowing. 
Perhaps there will be a more cohesive way to present these in the future.

### Storing Model Parameters

The LSH models all use randomized parameters to hash vectors. 
The simplest example is the bit-sampling model for Hamming similarity, parameterized by a list of randomly sampled indices. 
A more complicated example is the stable distributions model for L2 similarity, parameterized by a set of random unit vectors 
and a set of random bias scalars.
These parameters aren't actually stored anywhere in Elasticsearch. 
Rather, they are lazily re-computed from a fixed random seed (0) each time they are needed. 
The advantage of this is that it avoids storing and synchronizing potentially large parameter blobs in the cluster. 
The disadvantage is that it's expensive to re-compute the randomized parameters. 
So instead we keep a cache of models in each Elasticsearch node, keyed on the model hyperparameters (e.g. `L`, `k`, etc.). 
The hyperparameters are stored inside the mappings where they are originally defined.

### Transforming and Indexing Vectors

Each vector is transformed (e.g. hashed) based on its mapping when the user makes an indexing request. 
All vectors store a binary [doc values field](https://www.elastic.co/guide/en/elasticsearch/reference/current/doc-values.html) 
containing a serialized version of the vector for exact queries, and vectors indexed using an LSH model index the hashes
using a Lucene Term field. 
For example, for a sparse bool vector with a Jaccard LSH mapping, Elastiknn indexes the exact vector as a byte array in 
a doc values field and the vector's hash values as a set of Lucene Terms.

### Caching Mappings

When a user submits an `elastiknn_nearest_neighbors` query, Elastiknn has to retrieve the mapping for the indexed vector field in order to validate and hash the query vector. 
Mappings are typically static, so Elastiknn keeps an in-memory cache of mappings with a one-minute expiration to avoid repeatedly requesting an unchanged mapping for every query. 
This cache is local to each Elasticsearch node.

The practical implication is that if you intend to delete and re-create an index with different Elastiknn mappings, you should wait more than 60 seconds between deleting and running new queries. 
In reality it usually takes much longer than one minute to delete, re-create, and populate an index.

### Parallelism

From Elasticsearch's perspective, the `elastiknn_nearest_neighbors` query is no different than any other query. 
Elasticsearch receives a JSON query containing an `elastiknn_nearest_neighbors` key, passes the JSON to a parser implemented by Elastiknn, the parser produces a Lucene query, and Elasticsearch executes that query on each shard in the index. 
This means the simplest way to increase query parallelism is to add shards to your index. 
Obviously this has an upper limit, but the general performance implications of sharding are beyond the scope of this document.

### Use stored fields for faster queries

This is a fairly well-known Elasticsearch optimization that applies nicely to some elastiknn use cases.
If you only need to retrieve a small subset of the document source (e.g. only the ID), you can store the 
relavant fields as `stored` fields to get a meaningful speedup.
The Elastiknn scala client uses this optimization to store and retrieve document IDs, yielding a ~40% speedup.
The setting [is documented here](https://www.elastic.co/guide/en/elasticsearch/reference/current/mapping-store.html)
and discussed in detail [in this Github issue.](https://github.com/elastic/elasticsearch/issues/17159)
