/*
 * Copyright 2016 LinkedIn Corp. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linkedin.photon.ml.algorithm

import scala.collection.Set

import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD

import com.linkedin.photon.ml.constants.StorageLevel
import com.linkedin.photon.ml.data.{KeyValueScore, LabeledPoint, RandomEffectDataSet}
import com.linkedin.photon.ml.function.SingleNodeObjectiveFunction
import com.linkedin.photon.ml.model.{DatumScoringModel, RandomEffectModel}
import com.linkedin.photon.ml.optimization.game.{OptimizationTracker, RandomEffectOptimizationProblem, RandomEffectOptimizationTracker}
import com.linkedin.photon.ml.supervised.model.GeneralizedLinearModel

/**
 * The optimization problem coordinate for a random effect model
 *
 * @tparam Objective The type of objective function used to solve individual random effect optimization problems
 * @param dataSet The training dataset
 * @param optimizationProblem The random effect optimization problem
 */
protected[ml] abstract class RandomEffectCoordinate[Objective <: SingleNodeObjectiveFunction](
    dataSet: RandomEffectDataSet,
    optimizationProblem: RandomEffectOptimizationProblem[Objective])
  extends Coordinate[RandomEffectDataSet, RandomEffectCoordinate[Objective]](dataSet) {

  /**
   * Score the effect-specific data set in the coordinate with the input model
   *
   * @param model The input model
   * @return The output scores
   */
  override protected[algorithm] def score(model: DatumScoringModel): KeyValueScore = {
    model match {
      case randomEffectModel: RandomEffectModel => RandomEffectCoordinate.score(dataSet, randomEffectModel)

      case _ => throw new UnsupportedOperationException(s"Updating scores with model of type ${model.getClass} " +
          s"in ${this.getClass} is not supported!")
    }
  }

  /**
   * Compute an optimized model (i.e. run the coordinate optimizer) for the current dataset using an existing model as
   * a starting point
   *
   * @param model The model to use as a starting point
   * @return A tuple of the updated model and the optimization states tracker
   */
  override protected[algorithm] def updateModel(
    model: DatumScoringModel): (DatumScoringModel, Option[OptimizationTracker]) = model match {
        case randomEffectModel: RandomEffectModel =>
          RandomEffectCoordinate.updateModel(dataSet, optimizationProblem, randomEffectModel)

        case _ =>
          throw new UnsupportedOperationException(s"Updating model of type ${model.getClass} " +
              s"in ${this.getClass} is not supported!")
      }

  /**
   * Compute the regularization term value of the coordinate for a given model
   *
   * @param model The model
   * @return The regularization term value
   */
  override protected[algorithm] def computeRegularizationTermValue(model: DatumScoringModel): Double = model match {
    case randomEffectModel: RandomEffectModel =>
      optimizationProblem.getRegularizationTermValue(randomEffectModel.modelsRDD)

    case _ =>
      throw new UnsupportedOperationException(s"Compute the regularization term value with model of " +
          s"type ${model.getClass} in ${this.getClass} is not supported!")
  }
}

object RandomEffectCoordinate {
  /**
   * Update the model (i.e. run the coordinate optimizer)
   *
   * @tparam Function The type of objective function used to solve individual random effect optimization problems
   * @param randomEffectDataSet The training dataset
   * @param randomEffectOptimizationProblem The random effect optimization problem
   * @param randomEffectModel The current model, used as a starting point
   * @return A tuple of optimized model and optimization tracker
   */
  protected[algorithm] def updateModel[Function <: SingleNodeObjectiveFunction](
      randomEffectDataSet: RandomEffectDataSet,
      randomEffectOptimizationProblem: RandomEffectOptimizationProblem[Function],
      randomEffectModel: RandomEffectModel): (RandomEffectModel, Option[RandomEffectOptimizationTracker]) = {

    val updatedModels = randomEffectDataSet
      .activeData
      .join(randomEffectOptimizationProblem.optimizationProblems)
      .join(randomEffectModel.modelsRDD)
      .mapValues {
        case (((localDataSet, optimizationProblem), localModel)) =>
          val trainingLabeledPoints = localDataSet.dataPoints.map(_._2)

          optimizationProblem.run(trainingLabeledPoints, localModel)
      }
    val updatedRandomEffectModel = randomEffectModel
      .updateRandomEffectModel(updatedModels)
      .setName(s"Updated random effect model")
    val optimizationTracker = if (randomEffectOptimizationProblem.isTrackingState) {
        val stateTrackers = randomEffectOptimizationProblem.optimizationProblems.map(_._2.getStatesTracker.get)
        val randomEffectTracker = new RandomEffectOptimizationTracker(stateTrackers)
          .setName(s"Random effect optimization tracker")
          .persistRDD(StorageLevel.INFREQUENT_REUSE_RDD_STORAGE_LEVEL)
          .materialize()

        Some(randomEffectTracker)
      } else None

    (updatedRandomEffectModel, optimizationTracker)
  }

  /**
   * Score a dataset using a given model - i.e. just compute dot product of model coefficients with feature values
   * (in particular, does not go through non-linear link function in logistic regression!)
   *
   * @param randomEffectDataSet The active dataset to score
   * @param randomEffectModel The model to score the dataset with
   * @return The computed scores
   */
  protected[algorithm] def score(
    randomEffectDataSet: RandomEffectDataSet,
    randomEffectModel: RandomEffectModel): KeyValueScore = {

    val activeScores = randomEffectDataSet
      .activeData
      .join(randomEffectModel.modelsRDD)
      .flatMap { case (randomEffectId, (localDataSet, model)) =>
        localDataSet.dataPoints.map { case (uniqueId, labeledPoint) =>
          (uniqueId, model.computeScore(labeledPoint.features))
        }
      }
      .partitionBy(randomEffectDataSet.uniqueIdPartitioner)
      .setName("Active scores")
      .persist(StorageLevel.INFREQUENT_REUSE_RDD_STORAGE_LEVEL)

    val passiveDataOption = randomEffectDataSet.passiveDataOption
    if (passiveDataOption.isDefined) {
      val passiveDataRandomEffectIdsOption = randomEffectDataSet.passiveDataRandomEffectIdsOption
      val passiveScores = computePassiveScores(
          passiveDataOption.get,
          passiveDataRandomEffectIdsOption.get,
          randomEffectModel.modelsRDD)
        .setName("Passive scores")
        .persist(StorageLevel.INFREQUENT_REUSE_RDD_STORAGE_LEVEL)

      new KeyValueScore(activeScores ++ passiveScores)
    } else {
      new KeyValueScore(activeScores)
    }
  }

  // TODO: Explain passive data
  /**
   * Computes passive scores
   *
   * @param passiveData The passive dataset to score
   * @param passiveDataRandomEffectIds The set of random effect ids
   * @param modelsRDD The models for each individual id
   * @return The scores computed using the models
   */
  private def computePassiveScores(
      passiveData: RDD[(Long, (String, LabeledPoint))],
      passiveDataRandomEffectIds: Broadcast[Set[String]],
      modelsRDD: RDD[(String, GeneralizedLinearModel)]): RDD[(Long, Double)] = {

    val modelsForPassiveData = modelsRDD
      .filter { case (shardId, _) =>
        passiveDataRandomEffectIds.value.contains(shardId)
      }
      .collectAsMap()

    //TODO: Need a better design that properly unpersists the broadcasted variables and persists the computed RDD
    val modelsForPassiveDataBroadcast = passiveData.sparkContext.broadcast(modelsForPassiveData)
    val passiveScores = passiveData.mapValues { case (randomEffectId, labeledPoint) =>
      modelsForPassiveDataBroadcast.value(randomEffectId).computeScore(labeledPoint.features)
    }

    passiveScores.setName("Passive scores").persist(StorageLevel.INFREQUENT_REUSE_RDD_STORAGE_LEVEL).count()
    modelsForPassiveDataBroadcast.unpersist()

    passiveScores
  }
}
