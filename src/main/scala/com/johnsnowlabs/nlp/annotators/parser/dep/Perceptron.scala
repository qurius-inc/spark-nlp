package com.johnsnowlabs.nlp.annotators.parser.dep

import com.johnsnowlabs.nlp.annotators.parser.dep.GreedyTransition._

import scala.collection.mutable

class Perceptron(var numberOfClasses: Int) {
  // These need not be visible outside the class
  type TimeStamp = Int

  case class WeightLearner(current: Int, total: Int, ts: TimeStamp) {
    def addChange(change: Int) = {
      WeightLearner(current + change, total + current * (seen - ts), seen)
    }
  }

  type ClassToWeightLearner = mutable.Map[ClassNum, WeightLearner] // tells us the stats of each class (if present)

  // The following are keyed on feature (to keep tally of total numbers into each, and when)(for the TRAINING phase)
  var learning = mutable.Map.empty[
    String, // Corresponds to Feature.name
    mutable.Map[
      String, // Corresponds to Feature.data
      ClassToWeightLearner
      ]
    ] // This is hairy and mutable...

  // Number of instances seen - used to measure how 'old' each total is
  var seen: TimeStamp = 0

  def cleanLearning(): Unit = {
    learning = mutable.Map.empty[
    String, // Corresponds to Feature.name
    mutable.Map[
    String, // Corresponds to Feature.data
    ClassToWeightLearner
    ]
    ] // This is hairy and mutable...
  }

  type ClassVector = Vector[Score]

  def predict(classNumVector: ClassVector): ClassNum = { // Return best class guess for this vector of weights
    classNumVector.zipWithIndex.maxBy(_._1)._2 // in vector order (stabilizes) ///NOT : (and alphabetically too)
  }

  def current(w: WeightLearner): Double = w.current

  def average(w: WeightLearner): Double = (w.current * (seen - w.ts) + w.total) / seen // This is dynamically calculated
  // No need for average_weights() function - it's all done dynamically

  def score(features: Map[Feature, Score], scoreMethod: WeightLearner => Double): ClassVector = {
    features
      .filter { case (_, e2) => e2 != 0 case _ => false }
      .foldLeft(Vector.fill(numberOfClasses)(0: Double)) {
        case (acc, (Feature(name, data), score)) =>
          learning
            .getOrElse(name, Map[String,ClassToWeightLearner]())
            .getOrElse(data, Map[ClassNum, WeightLearner]())
            .foldLeft(acc) { (accForFeature, cnWl) =>
              val classnum: ClassNum = cnWl._1
              val weightLearner: WeightLearner = cnWl._2
              accForFeature.updated(classnum, accForFeature(classnum) + score * scoreMethod(weightLearner))
            }
      }
  }

  def dotProductScore(features: Map[Feature, Score], scoreMethod: WeightLearner => Double): ClassVector = {
    // Return 'dot-product' score for all classes
    //  This is the mutable version : 2493ms for 1 train_all, and 0.45ms for a sentence
    val scores = new Array[Score](numberOfClasses) // All 0?
    //var indexTemp = 0
    features
      .filter(pair => pair._2 != 0) // if the 'score' multiplier is zero, skip
      .foreach { case (Feature(name, data), score) => { // Ok, so given a particular feature, and score to weight it by
      if (learning.contains(name) && learning(name).contains(data)) {
        learning(name)(data).foreach { case (classNum, weightLearner) => {
          scores(classNum) += score * scoreMethod(weightLearner)
        }
        }
      }
    }
    }
    scores.toVector
  }

  def update(truth: ClassNum, guess: ClassNum, features: Iterable[Feature]): Unit = { // Hmmm ..Unit..
    seen += 1
    if (truth != guess) {
      for {
        feature <- features
      } {
        learning.getOrElseUpdate(feature.name, mutable.Map[FeatureData, ClassToWeightLearner]())
        var thisMap = learning(feature.name).getOrElseUpdate(feature.data, mutable.Map[ClassNum, WeightLearner]())

        if (thisMap.contains(guess)) {
          thisMap.update(guess, thisMap(guess).addChange(-1))
        }
        thisMap.update(truth, thisMap.getOrElse(truth, WeightLearner(0, 0, seen)).addChange(+1))

        learning(feature.name)(feature.data) = thisMap
      }
    }
  }

  override def toString(): String = {
    s"perceptron.seen=[$seen]\n" +
      learning.map({ case (featureName, m1) => {
        m1.map({ case (featureData, cnFeature) => {
          cnFeature.map({ case (cn, feature) => {
            s"$cn:${feature.current},${feature.total},${feature.ts}"
          }
          }).mkString(s"$featureData[", "|", "]\n")
        }
        }).mkString(s"$featureName{\n", "", "}\n")
      }
      }).mkString("perceptron.learning={\n", "", "}\n")
  }

  def load(lines: Iterator[String]): Unit = {
    val perceptronSeen = """perceptron.seen=\[(.*)\]""".r
    val perceptronFeatN = """(.*)\{""".r
    val perceptronFeatD = """(.*)\[(.*)\]""".r

    def parse(lines: Iterator[String]): Unit = if (lines.hasNext) lines.next match {
      case perceptronSeen(data) => {
        seen = data.toInt
        parse(lines)
      }
      case "perceptron.learning={" => {
        parseFeatureName(lines)
        parse(lines)
      }
      case _ => () // line not understood : Finished with perceptron
    }

    def parseFeatureName(lines: Iterator[String]): Unit = if (lines.hasNext) lines.next match {
      case perceptronFeatN(feature_name) => {
        learning.getOrElseUpdate(feature_name, mutable.Map[FeatureData, ClassToWeightLearner]())
        parseFeatureData(feature_name, lines)
        parseFeatureName(lines) // Go back for more featurename sections
      }
      case _ => () // line not understood : Finished with featurename
    }

    def parseFeatureData(featureName: String, lines:
    Iterator[String]): Unit = if (lines.hasNext) lines.next match {
      case perceptronFeatD(feature_data, classNumWeights) => {
        learning(featureName).getOrElseUpdate(feature_data, mutable.Map[ClassNum, WeightLearner]())
        classNumWeights.split('|').map(classNumWeight => {
          val classNumWeightArray = classNumWeight.split(':').map(_.split(',').map(_.toInt))
          learning(featureName)(feature_data) += ((classNumWeightArray(0)(0), WeightLearner(classNumWeightArray(1)(0),
                                                  classNumWeightArray(1)(1), classNumWeightArray(1)(2))))
        })
        parseFeatureData(featureName, lines) // Go back for more featuredata lines
      }
      case _ => () // line not understood : Finished with featuredata
    }

    parse(lines)
  }

}
