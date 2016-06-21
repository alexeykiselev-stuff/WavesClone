package scorex.transaction.state.database.blockchain

import java.io.{File, FileReader}

import com.opencsv.CSVReader
import com.typesafe.config.ConfigFactory
import org.h2.mvstore.MVStore
import scopt.OptionParser
import scorex.account.PrivateKeyAccount
import scorex.api.http.BlocksApiRoute
import scorex.app.ApplicationVersion
import scorex.block.Block
import scorex.consensus.ConsensusModule
import scorex.consensus.nxt.NxtLikeConsensusBlockData
import scorex.crypto.encode.Base58
import scorex.network.TransactionalMessagesRepo
import scorex.transaction.{PaymentTransaction, SimpleTransactionModule, Transaction}
import scorex.waves.consensus.WavesConsensusModule
import scorex.waves.settings.WavesSettings
import scorex.waves.transaction.WavesTransactionModule

import scala.collection.JavaConversions._
import scala.reflect.runtime.universe._
import scala.util.Failure

class Rebuild(val settingsFilename: File, destination: File,
              accounts: Seq[(String, Array[Byte], Array[Byte], Array[Byte])],
              transactionsToReplace: Map[String, Transaction],
              transactionsToRemove: Seq[String]) extends scorex.app.Application {
  override val applicationName = "waves"
  private val appConf = ConfigFactory.load().getConfig("app")
  override val appVersion = {
    val raw = appConf.getString("version")
    val parts = raw.split("\\.")
    ApplicationVersion(parts(0).toInt, parts(1).toInt, parts(2).split("-").head.toInt)
  }

  override lazy val apiRoutes = Seq(BlocksApiRoute(this))
  override lazy val apiTypes = Seq(typeOf[BlocksApiRoute])


  override implicit lazy val settings = new WavesSettings(settingsFilename.getPath)
  override implicit lazy val consensusModule = new WavesConsensusModule()
  override implicit lazy val transactionModule: SimpleTransactionModule = new WavesTransactionModule()(settings, this)


  def rebuild() = {
    destination.mkdirs()
    destination.delete()

    val oldBlockchain = transactionModule.blockStorage.history.asInstanceOf[StoredBlockchain]
    val newStorage = new MVStore.Builder().fileName(destination.getPath).compress().open()
    val newBlockchain = new StoredBlockchain(newStorage)
    val newState = new StoredState(newStorage)

    val privateKeys: Map[String, PrivateKeyAccount] = accounts.map(a =>
      a._1 -> new PrivateKeyAccount(a._2, a._3, a._4)).toMap


    var ref: Array[Byte] = oldBlockchain.blockAt(1).get.referenceField.value
    newBlockchain.appendBlock(oldBlockchain.blockAt(1).get)
    newState.processBlock(oldBlockchain.blockAt(1).get)
    ref = newBlockchain.lastBlock.uniqueId

    require(oldBlockchain.height() > 1)

    (2 to oldBlockchain.height()) foreach { height =>
      val oldBlock = oldBlockchain.blockAt(height).get

      val cleanBlockTransactions: Seq[Transaction] = oldBlock.transactions
        .filterNot(t => transactionsToRemove contains Base58.encode(t.signature))

      val blockTransactions: Seq[Transaction] = cleanBlockTransactions.
        map(t => transactionsToReplace.getOrElse(Base58.encode(t.signature), t))

      val privateKey: PrivateKeyAccount = privateKeys(oldBlock.signerDataField.value.generator.address)
      implicit val cm: ConsensusModule[NxtLikeConsensusBlockData] = consensusModule

      val newBlock = Block.buildAndSign(oldBlock.versionField.value,
        oldBlock.timestampField.value,
        ref,
        oldBlock.consensusDataField.value.asInstanceOf[NxtLikeConsensusBlockData],
        blockTransactions,
        privateKey)(cm, transactionModule)

      newBlockchain.appendBlock(newBlock) match {
        case Failure(e) => throw e
        case _ =>
      }

      newState.processBlock(newBlock) match {
        case Failure(e) => throw e
        case _ =>
      }

      ref = newBlock.uniqueId
      newStorage.commit()
    }
    log.info(s"Blockchain recovered with ${oldBlockchain.height()}!")
    stopAll()
  }

  //checks
  require(transactionModule.balancesSupport)
  require(transactionModule.accountWatchingSupport)
  override lazy val additionalMessageSpecs = TransactionalMessagesRepo.specs
}

case class RebuildConfiguration(
                                 config: File = new File("settings-local1.json"),
                                 destination: File = new File("newBlockchain.dat"),
                                 accounts: File = new File("accounts.csv"),
                                 transactions: File = new File("transactions.csv"))

object Rebuild extends App {
  override def main(args: Array[String]) {
    val parser = new OptionParser[RebuildConfiguration]("rebuild") {
      head("Rebuild - Waves blockchain rebuild tool", "v1.0.0")
      opt[File]('c', "config") required() valueName "<file>" action { (x, c) =>
        c.copy(config = x)
      } validate { x =>
        if (x.exists()) success else failure(s"Failed to open file $x")
      } text "path configuration file"
      opt[File]('d', "destination") required() valueName "<file>" action { (x, c) =>
        c.copy(destination = x)
      } text "path new blockchain file"
      opt[File]('a', "accounts") required() valueName "<file>" action { (x, c) =>
        c.copy(accounts = x)
      } validate { x =>
        if (x.exists()) success else failure(s"Failed to open file $x")
      } text "path generation accounts file"
      opt[File]('t', "transactions") required() valueName "<file>" action { (x, c) =>
        c.copy(transactions = x)
      } validate { x =>
        if (x.exists()) success else failure(s"Failed to open file $x")
      } text "path transactions substitution file"
      help("help") text "display this help message"
    }
    parser.parse(args, RebuildConfiguration()) match {
      case Some(config) =>
        val accountsReader = new CSVReader(new FileReader(config.accounts))
        val accounts = accountsReader.readAll.filter(row => row.length == 4).map(
          row => (row(0).trim, Base58.decode(row(1)).get, Base58.decode(row(2)).get, Base58.decode(row(3)).get))
        accountsReader.close()

        val transactionsToReplaceReader = new CSVReader(new FileReader(config.transactions))
        val transactionsToReplace: Map[String, Transaction] =
          transactionsToReplaceReader.readAll.filter(row => row.length == 2)
            .map(row => row(0) -> PaymentTransaction.parseBytes(Base58.decode(row(1).trim).get).get)
            .toMap[String, Transaction]
        transactionsToReplaceReader.close()

        val transactionsToRemoveReader = new CSVReader(new FileReader(config.transactions))
        val transactionsToRemove: Seq[String] =
          transactionsToRemoveReader.readAll().filter(row => row.length == 1)
            .map(row => row(0).trim)
        transactionsToRemoveReader.close()

        val rebuild =
          new Rebuild(config.config, config.destination, accounts, transactionsToReplace, transactionsToRemove)
        rebuild.rebuild()
      case None =>
        println("Incorrect parameters")
    }
  }
}