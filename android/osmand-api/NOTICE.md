# OsmAnd AIDL API (copy)

`src/` and `res/` are an unmodified copy of the `OsmAnd-api` module of OsmAnd
(https://github.com/osmandapp/OsmAnd, commit `3c5d61fcaabd4fb3489aa224855cc4cd79173c3c`). V2X2MAP uses it only to talk to an installed
OsmAnd app through OsmAnd's documented AIDL interface (map layer with V2X points, status widget).

OsmAnd documents the AIDL API as usable without licence restrictions ("No License issues - available
for all possible purposes", https://www.osmand.net/docs/technical/osmand-api-sdk/; "For the OsmAnd API
the least restrictive license is used, the MIT license", https://docs.osmand.net/docs/build-it).
Note that the OsmAnd repository's top-level LICENSE does not list this module separately; check with
the OsmAnd project before redistributing V2X2MAP builds widely.

Compatibility: the interface methods of OsmAnd 5.4 (branch r5.4) are an unchanged prefix of this
copy; V2X2MAP only calls methods that exist in 5.4 (map layers/points, map widgets).
