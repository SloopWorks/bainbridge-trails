"""Road dataset pipeline (`walk/bike every road on Bainbridge Island`).

A second, independent dataset built from its own sources (data/roads/sources.roads.yaml) into its
own tree (data/roads/), sharing the trail pipeline's machinery: fetch, island clip, junction
splitting, reconcile, the stable-id registry and the DEM elevation sampler. Nothing in this package
imports from the app or writes anywhere under data/ except data/roads/, so the trail dataset and the
v1 app are untouched by a roads build.
"""
