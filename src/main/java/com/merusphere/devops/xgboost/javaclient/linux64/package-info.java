/**
 * A Java client for XGBoost over the Foreign Function and Memory API.
 *
 * <p>Three types carry the whole surface:
 * <ul>
 *   <li>{@link com.merusphere.devops.xgboost.javaclient.linux64.Xgb} &mdash; library
 *       loading, version, global configuration</li>
 *   <li>{@link com.merusphere.devops.xgboost.javaclient.linux64.DMatrix} &mdash; the
 *       data a model trains on or scores</li>
 *   <li>{@link com.merusphere.devops.xgboost.javaclient.linux64.Booster} &mdash; the
 *       model itself</li>
 * </ul>
 *
 * <p>Below this package, {@code internal} holds the FFM plumbing and
 * {@code capi} holds the jextract-generated bindings. Neither is part of the
 * supported API: {@code capi} is regenerated wholesale from {@code c_api.h} by
 * {@code scripts/02-generate-bindings.sh}, and {@code internal} exists only to
 * keep the pointer handling out of the classes above.
 *
 * <p>{@code DMatrix} and {@code Booster} own native memory and must be closed.
 * Neither is thread-safe.
 */
package com.merusphere.devops.xgboost.javaclient.linux64;
