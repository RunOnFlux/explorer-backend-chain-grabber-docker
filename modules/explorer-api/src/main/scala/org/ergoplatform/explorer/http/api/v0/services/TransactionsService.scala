package org.ergoplatform.explorer.http.api.v0.services

import cats.syntax.list._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.applicative._
import cats.syntax.traverse._
import cats.instances.list._
import cats.{~>, Monad}
import mouse.anyf._
import fs2.Stream
import org.ergoplatform.ErgoAddressEncoder
import org.ergoplatform.explorer.db.repositories._
import org.ergoplatform.explorer.http.api.models.Paging
import org.ergoplatform.explorer.http.api.v0.models.{TransactionInfo, UTransactionInfo}
import org.ergoplatform.explorer.protocol.utils
import org.ergoplatform.explorer.syntax.stream._
import org.ergoplatform.explorer.{Address, Err, TxId}
import tofu.Raise.ContravariantRaise

/** A service providing an access to the transactions data.
  */
trait TransactionsService[F[_], S[_[_], _]] {

  /** Get transaction info by `id`.
    */
  def getTxInfo(id: TxId): F[Option[TransactionInfo]]

  /** Get transactions related to a given `address`.
    */
  def getTxsInfoByAddress(address: Address, paging: Paging): S[F, TransactionInfo]

  /** Get unconfirmed transactions related to a given `address`.
    */
  def getUnconfirmedTxsByAddress(address: Address, paging: Paging): S[F, UTransactionInfo]
}

object TransactionsService {

  final private class Live[F[_], D[_]: ContravariantRaise[*[_], Err]: Monad](
    headerRepo: HeaderRepo[D],
    transactionRepo: TransactionRepo[D, Stream],
    uTransactionRepo: UTransactionRepo[D, Stream],
    inputRepo: InputRepo[D],
    uInputRepo: UInputRepo[D, Stream],
    outputRepo: OutputRepo[D, Stream],
    uOutputRepo: UOutputRepo[D, Stream],
    assetRepo: AssetRepo[D, Stream],
    uAssetRepo: UAssetRepo[D]
  )(xa: D ~> F)(implicit e: ErgoAddressEncoder)
    extends TransactionsService[F, Stream] {

    def getTxInfo(id: TxId): F[Option[TransactionInfo]] =
      (for {
        txOpt <- transactionRepo.getMain(id)
        ins   <- txOpt.toList.flatTraverse(tx => inputRepo.getAllByTxId(tx.id))
        outs  <- txOpt.toList.flatTraverse(tx => outputRepo.getAllByTxId(tx.id))
        boxIdsNel = outs.map(_.output.boxId).toNel
        assets     <- boxIdsNel.toList.flatTraverse(assetRepo.getAllByBoxIds)
        bestHeight <- headerRepo.getBestHeight
        txInfo = txOpt.map(
          tx => TransactionInfo(tx, bestHeight - tx.inclusionHeight, ins, outs, assets)
        )
      } yield txInfo) ||> xa

    def getTxsInfoByAddress(
      address: Address,
      paging: Paging
    ): Stream[F, TransactionInfo] =
      (for {
        txChunk <- transactionRepo
                    .getRelatedToAddress(address, paging.offset, paging.limit)
                    .chunkN(100)
        txIdsNel   <- txChunk.map(_.id).toList.toNel.toStream.covary[D]
        ins        <- inputRepo.getAllByTxIds(txIdsNel).asStream
        outs       <- outputRepo.getAllByTxIds(txIdsNel).asStream
        boxIdsNel  <- outs.map(_.output.boxId).toNel.toStream.covary[D]
        assets     <- assetRepo.getAllByBoxIds(boxIdsNel).asStream
        bestHeight <- headerRepo.getBestHeight.asStream
        txsWithHeights = txChunk.map(tx => tx -> (bestHeight - tx.inclusionHeight)).toList
        txInfo <- Stream
                   .emits(TransactionInfo.batch(txsWithHeights, ins, outs, assets))
                   .covary[D]
      } yield txInfo).translate(xa)

    def getUnconfirmedTxsByAddress(
      address: Address,
      paging: Paging
    ): Stream[F, UTransactionInfo] =
      (for {
        ergoTree <- utils.addressToErgoTreeHex(address).asStream
        txChunk <- uTransactionRepo
                    .getAllRelatedToErgoTree(ergoTree, paging.offset, paging.limit)
                    .chunkN(100)
        txIdsNel  <- txChunk.map(_.id).toList.toNel.toStream.covary[D]
        ins       <- uInputRepo.getAllByTxIds(txIdsNel).asStream
        outs      <- uOutputRepo.getAllByTxIds(txIdsNel).asStream
        boxIdsNel <- outs.map(_.boxId).toNel.toStream.covary[D]
        assets    <- uAssetRepo.getAllByBoxIds(boxIdsNel).asStream
        utxInfo   <- Stream.emits(UTransactionInfo.batch(txChunk.toList, ins, outs, assets))
      } yield utxInfo).translate(xa)
  }
}
