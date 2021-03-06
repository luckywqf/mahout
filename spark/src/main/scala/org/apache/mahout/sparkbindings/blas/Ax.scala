package org.apache.mahout.sparkbindings.blas

import org.apache.mahout.math._
import scalabindings._
import RLikeOps._
import drm._
import org.apache.mahout.sparkbindings._
import org.apache.mahout.sparkbindings.drm.DrmRddInput
import scala.reflect.ClassTag
import org.apache.mahout.math.drm.logical.{OpAx, OpAtx}


/** Matrix product with one of operands an in-core matrix */
object Ax {

  def ax_with_broadcast[K: ClassTag](op: OpAx[K], srcA: DrmRddInput[K]): DrmRddInput[K] = {

    val rddA = srcA.toBlockifiedDrmRdd()
    implicit val sc:DistributedContext = rddA.sparkContext

    val bcastX = drmBroadcast(op.x)

    val rdd = rddA
        // Just multiply the blocks
        .map({
      case (keys, blockA) => keys -> (blockA %*% bcastX).toColMatrix
    })

    new DrmRddInput(blockifiedSrc = Some(rdd))
  }

  def atx_with_broadcast(op: OpAtx, srcA: DrmRddInput[Int]): DrmRddInput[Int] = {
    val rddA = srcA.toBlockifiedDrmRdd()
    implicit val dc:DistributedContext = rddA.sparkContext

    val bcastX = drmBroadcast(op.x)

    val inCoreM = rddA
        // Just multiply the blocks
        .map {
      case (keys, blockA) =>
        keys.zipWithIndex.map {
          case (key, idx) => blockA(idx, ::) * bcastX.value(key)
        }
            .reduce(_ += _)
    }
        // All-reduce
        .reduce(_ += _)
        // Convert back to mtx
        .toColMatrix

    // It is ridiculous, but in this scheme we will have to re-parallelize it again in order to plug
    // it back as drm blockified rdd

    val rdd = dc.parallelize(Seq(inCoreM), numSlices = 1)
        .map(block => Array.tabulate(block.nrow)(i => i) -> block)

    new DrmRddInput(blockifiedSrc = Some(rdd))

  }

}
