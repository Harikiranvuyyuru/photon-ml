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
package com.linkedin.photon.ml.normalization

import breeze.linalg.{DenseVector, Vector}

import com.linkedin.photon.ml.stat.BasicStatisticalSummary

/**
  * The normalization approach for the optimization problem, especially for generalized linear model.
  *
  * The transformation consists of two parts, a translational shift and a scaling factor.
  * The normalization of a feature vector x is:
  *
  * x[i] -> (x[i] - shift) .* factor
  *
  * The normalized vector has mean 0 and unit variance.
  * If the shift is enabled, there must be an intercept provided.
  * Normalization allows regularization to be applied evenly to all the features (same scale).
  *
  * This class assume that the intercepts for the original and the transformed space are both 1, so the shift for the
  * intercept should be 0, and the factor for the intercept should be 1.
  *
  * Also note that this normalization context class covers all affine transformations without rotation.
  */
private[ml] case class NormalizationContext(
    factors: Option[_ <: Vector[Double]],
    shifts: Option[_ <: Vector[Double]],
    interceptId: Option[Int]) {

  require(!(shifts.isDefined && interceptId.isEmpty), "Shift without intercept is illegal.")
  if (factors.isDefined && shifts.isDefined) {
    require(factors.get.size == shifts.get.size, "Factors and shifts vectors should have the same size")
  }

  /**
   * Transform the coefficients of the transformed space to the original space. This is typically used to
   * transform models in the transformed space to the models in the original space for other usages.
   *
   * The key requirement for the transformation is to keep the margin consistent in both space, i.e.
   *
   * w^T^ x + b = w'^T^ x' + b' = w'^T^ [(x - shift) .* factor] + b'
   *
   * where b is the explicit intercept, and .* is a point wise multiplication.
   *
   * To make the equation work for all x, we have
   *
   * w = w' .* factor
   *
   * and
   *
   * b = - w'^T^ shift + b'
   *
   * @param inputCoef The coefficients + the intercept in the transformed space
   * @return The coefficients + the intercept in the original space
   */
  def transformModelCoefficients(inputCoef: Vector[Double]): Vector[Double] = {
    val outputCoef = factors match {
      case Some(fs) =>
        inputCoef :* fs
      case None =>
        inputCoef.copy
    }
    // All shifts go to intercept
    shifts.foreach(ss => {
      outputCoef(interceptId.get) -= outputCoef.dot(ss)
    })
    outputCoef
  }

}

private[ml] object NormalizationContext {
  /**
   * A factory method to create normalization context according to the [[NormalizationType]] and the
   * feature summary. If using [[NormalizationType]].STANDARDIZATION, an intercept index is also needed.
   *
   * @param normalizationType The normalization type
   * @param summary Feature summary
   * @param interceptId The index of the intercept
   * @return The normalization context
   */
  def apply(
      normalizationType: NormalizationType,
      summary: => BasicStatisticalSummary,
      interceptId: Option[Int]): NormalizationContext = normalizationType match {

    case NormalizationType.NONE =>
      new NormalizationContext(None, None, interceptId)

    case NormalizationType.SCALE_WITH_MAX_MAGNITUDE =>
      val factors = summary.max.toArray.zip(summary.min.toArray).map {
        case (max, min) =>
          val magnitude = math.max(math.abs(max), math.abs(min))
          if (magnitude == 0) 1.0 else 1.0 / magnitude
        }
      new NormalizationContext(Some(DenseVector(factors)), None, interceptId)

    case NormalizationType.SCALE_WITH_STANDARD_DEVIATION =>
      val factors = summary.variance.map(x => {
        val std = math.sqrt(x)
        if (std == 0) 1.0 else 1.0 / std
      })
      new NormalizationContext(Some(factors), None, interceptId)

    case NormalizationType.STANDARDIZATION =>
      val factors = summary.variance.map(x => {
        val std = math.sqrt(x)
        if (std == 0) 1.0 else 1.0 / std
      })
      val shifts = summary.mean.copy
      // Do not transform intercept
      interceptId.foreach(id => {
        shifts(id) = 0.0
        factors(id) = 1.0
      })
      new NormalizationContext(Some(factors), Some(shifts), interceptId)

    case _ =>
      throw new IllegalArgumentException(s"NormalizationType $normalizationType not recognized.")
  }
}

/**
 * Factory to create contexts for no normalization.
 */
private[ml] object NoNormalization {
  def apply(): NormalizationContext = NormalizationContext(factors = None, shifts = None, interceptId = None)
}
