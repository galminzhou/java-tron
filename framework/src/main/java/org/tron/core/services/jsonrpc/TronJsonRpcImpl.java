package org.tron.core.services.jsonrpc;

import static org.tron.core.Wallet.CONTRACT_VALIDATE_ERROR;
import static org.tron.core.Wallet.CONTRACT_VALIDATE_EXCEPTION;
import static org.tron.core.services.http.Util.setTransactionExtraData;
import static org.tron.core.services.http.Util.setTransactionPermissionId;
import static org.tron.core.services.jsonrpc.JsonRpcApiUtil.addressHashToByteArray;
import static org.tron.core.services.jsonrpc.JsonRpcApiUtil.getEnergyUsageTotal;
import static org.tron.core.services.jsonrpc.JsonRpcApiUtil.getTransactionIndex;
import static org.tron.core.services.jsonrpc.JsonRpcApiUtil.getTxID;
import static org.tron.core.services.jsonrpc.JsonRpcApiUtil.triggerCallContract;

import com.alibaba.fastjson.JSON;
import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessageV3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.tron.api.GrpcAPI.BytesMessage;
import org.tron.api.GrpcAPI.Return;
import org.tron.api.GrpcAPI.Return.response_code;
import org.tron.api.GrpcAPI.TransactionExtention;
import org.tron.common.crypto.Hash;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.runtime.vm.DataWord;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.ByteUtil;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.Wallet;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.db.Manager;
import org.tron.core.db2.core.Chainbase;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.exception.HeaderNotFound;
import org.tron.core.exception.JsonRpcInternalException;
import org.tron.core.exception.JsonRpcInvalidParamsException;
import org.tron.core.exception.JsonRpcInvalidRequestException;
import org.tron.core.exception.JsonRpcMethodNotFoundException;
import org.tron.core.exception.VMIllegalException;
import org.tron.core.services.NodeInfoService;
import org.tron.core.services.http.JsonFormat;
import org.tron.core.services.http.Util;
import org.tron.core.store.StorageRowStore;
import org.tron.core.vm.config.VMConfig;
import org.tron.core.vm.program.Storage;
import org.tron.program.Version;
import org.tron.protos.Protocol.Account;
import org.tron.protos.Protocol.Block;
import org.tron.protos.Protocol.Transaction;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.Protocol.TransactionInfo;
import org.tron.protos.contract.AssetIssueContractOuterClass.TransferAssetContract;
import org.tron.protos.contract.BalanceContract.TransferContract;
import org.tron.protos.contract.SmartContractOuterClass.CreateSmartContract;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract.ABI;
import org.tron.protos.contract.SmartContractOuterClass.SmartContractDataWrapper;
import org.tron.protos.contract.SmartContractOuterClass.TriggerSmartContract;

@Slf4j(topic = "API")
public class TronJsonRpcImpl implements TronJsonRpc {

  public enum RequestSource {
    FULLNODE,
    SOLIDITY,
    PBFT
  }

  String regexHash = "(0x)?[a-zA-Z0-9]{64}$";
  private final int chainId = 100;
  private final int networkId = 100;

  private NodeInfoService nodeInfoService;
  private Wallet wallet;
  private Manager manager;

  public TronJsonRpcImpl(NodeInfoService nodeInfoService, Wallet wallet, Manager manager) {
    this.nodeInfoService = nodeInfoService;
    this.wallet = wallet;
    this.manager = manager;
  }

  @Override
  public String web3ClientVersion() {
    Pattern shortVersion = Pattern.compile("(\\d\\.\\d).*");
    Matcher matcher = shortVersion.matcher(System.getProperty("java.version"));
    matcher.matches();

    return Arrays.asList(
        "TRON", "v" + Version.getVersion(),
        System.getProperty("os.name"),
        "Java" + matcher.group(1),
        Version.VERSION_NAME).stream()
        .collect(Collectors.joining("/"));
  }

  @Override
  public String web3Sha3(String data) throws JsonRpcInvalidParamsException {
    byte[] input;
    try {
      input = ByteArray.fromHexString(data);
    } catch (Exception e) {
      throw new JsonRpcInvalidParamsException("invalid input value");
    }

    byte[] result = Hash.sha3(input);
    return ByteArray.toJsonHex(result);
  }

  @Override
  public String ethGetBlockTransactionCountByHash(String blockHash)
      throws JsonRpcInvalidParamsException {
    Block b = getBlockByJsonHash(blockHash);
    if (b == null) {
      return null;
    }

    long n = b.getTransactionsList().size();
    return ByteArray.toJsonHex(n);
  }

  @Override
  public String ethGetBlockTransactionCountByNumber(String blockNumOrTag)
      throws JsonRpcInvalidParamsException {
    List<Transaction> list = wallet.getTransactionsByJsonBlockId(blockNumOrTag);
    if (list == null) {
      return null;
    }

    long n = list.size();
    return ByteArray.toJsonHex(n);
  }

  @Override
  public BlockResult ethGetBlockByHash(String blockHash, Boolean fullTransactionObjects)
      throws JsonRpcInvalidParamsException {
    final Block b = getBlockByJsonHash(blockHash);
    return getBlockResult(b, fullTransactionObjects);
  }

  @Override
  public BlockResult ethGetBlockByNumber(String blockNumOrTag, Boolean fullTransactionObjects)
      throws JsonRpcInvalidParamsException {
    final Block b = wallet.getByJsonBlockId(blockNumOrTag);
    return (b == null ? null : getBlockResult(b, fullTransactionObjects));
  }

  private byte[] hashToByteArray(String hash) throws JsonRpcInvalidParamsException {
    if (!Pattern.matches(regexHash, hash)) {
      throw new JsonRpcInvalidParamsException("invalid hash value");
    }

    byte[] bHash;
    try {
      bHash = ByteArray.fromHexString(hash);
    } catch (Exception e) {
      throw new JsonRpcInvalidParamsException(e.getMessage());
    }
    return bHash;
  }

  private Block getBlockByJsonHash(String blockHash) throws JsonRpcInvalidParamsException {
    byte[] bHash = hashToByteArray(blockHash);
    return wallet.getBlockById(ByteString.copyFrom(bHash));
  }

  private BlockResult getBlockResult(Block block, boolean fullTx) {
    if (block == null) {
      return null;
    }

    BlockCapsule blockCapsule = new BlockCapsule(block);
    BlockResult br = new BlockResult();
    br.number = ByteArray.toJsonHex(blockCapsule.getNum());
    br.hash = ByteArray.toJsonHex(blockCapsule.getBlockId().getBytes());
    br.parentHash = ByteArray.toJsonHex(blockCapsule.getParentBlockId().getBytes());
    br.nonce = null; // no value
    br.sha3Uncles = null; // no value
    br.logsBloom = ByteArray.toJsonHex(new byte[256]); // no value
    br.transactionsRoot = ByteArray
        .toJsonHex(block.getBlockHeader().getRawData().getTxTrieRoot().toByteArray());
    br.stateRoot = ByteArray
        .toJsonHex(block.getBlockHeader().getRawData().getAccountStateRoot().toByteArray());
    br.receiptsRoot = null; // no value
    br.miner = ByteArray.toJsonHexAddress(blockCapsule.getWitnessAddress().toByteArray());
    br.difficulty = null; // no value
    br.totalDifficulty = null; // no value
    br.extraData = null; // no value
    br.size = ByteArray.toJsonHex(block.getSerializedSize());
    br.timestamp = ByteArray.toJsonHex(blockCapsule.getTimeStamp());

    long gasUsedInBlock = 0;
    long gasLimitInBlock = 0;

    List<Object> txes = new ArrayList<>();
    List<Transaction> transactionsList = block.getTransactionsList();
    List<TransactionInfo> transactionInfoList =
        wallet.getTransactionInfoByBlockNum(blockCapsule.getNum()).getTransactionInfoList();
    if (fullTx) {
      long energyFee = wallet.getEnergyFee(blockCapsule.getTimeStamp());

      for (int i = 0; i < transactionsList.size(); i++) {
        Transaction transaction = transactionsList.get(i);
        gasLimitInBlock += transaction.getRawData().getFeeLimit();

        long energyUsageTotal = getEnergyUsageTotal(transactionInfoList, i, blockCapsule.getNum());
        gasUsedInBlock += energyUsageTotal;

        txes.add(new TransactionResult(blockCapsule, i, transaction,
            energyUsageTotal, energyFee, wallet));
      }
    } else {
      for (int i = 0; i < transactionsList.size(); i++) {
        gasLimitInBlock += transactionsList.get(i).getRawData().getFeeLimit();
        gasUsedInBlock += getEnergyUsageTotal(transactionInfoList, i, blockCapsule.getNum());

        byte[] txHash = Sha256Hash
            .hash(CommonParameter.getInstance().isECKeyCryptoEngine(),
                transactionsList.get(i).getRawData().toByteArray());
        txes.add(ByteArray.toJsonHex(txHash));
      }
    }
    br.transactions = txes.toArray();

    br.gasLimit = ByteArray.toJsonHex(gasLimitInBlock);
    br.gasUsed = ByteArray.toJsonHex(gasUsedInBlock);
    List<String> ul = new ArrayList<>();
    br.uncles = ul.toArray(new String[0]);

    return br;
  }

  @Override
  public String getNetVersion() throws JsonRpcInternalException {
    return ethChainId();
  }

  @Override
  public String ethChainId() throws JsonRpcInternalException {
    // return hash of genesis block
    try {
      byte[] chainId = wallet.getBlockCapsuleByNum(0).getBlockId().getBytes();
      return ByteArray.toJsonHex(Arrays.copyOfRange(chainId, chainId.length - 4, chainId.length));
    } catch (Exception e) {
      throw new JsonRpcInternalException(e.getMessage());
    }
  }

  @Override
  public boolean isListening() {
    int activeConnectCount = nodeInfoService.getNodeInfo().getActiveConnectCount();
    return activeConnectCount >= 1;
  }

  @Override
  public String getProtocolVersion() {
    return ByteArray.toJsonHex(wallet.getNowBlock().getBlockHeader().getRawData().getVersion());
  }

  @Override
  public String getLatestBlockNum() {
    return ByteArray.toJsonHex(wallet.getNowBlock().getBlockHeader().getRawData().getNumber());
  }

  @Override
  public String getTrxBalance(String address, String blockNumOrTag)
      throws JsonRpcInvalidParamsException {
    if ("earliest".equalsIgnoreCase(blockNumOrTag)
        || "pending".equalsIgnoreCase(blockNumOrTag)) {
      throw new JsonRpcInvalidParamsException("TAG [earliest | pending] not supported");
    } else if ("latest".equalsIgnoreCase(blockNumOrTag)) {
      byte[] addressData = addressHashToByteArray(address);

      Account account = Account.newBuilder().setAddress(ByteString.copyFrom(addressData)).build();
      Account reply = wallet.getAccount(account);
      long balance = 0;

      if (reply != null) {
        balance = reply.getBalance();
      }
      return ByteArray.toJsonHex(balance);
    } else {
      try {
        ByteArray.hexToBigInteger(blockNumOrTag);
      } catch (Exception e) {
        throw new JsonRpcInvalidParamsException("invalid block number");
      }

      throw new JsonRpcInvalidParamsException("QUANTITY not supported, just support TAG as latest");
    }
  }

  private void callTriggerConstantContract(byte[] ownerAddressByte, byte[] contractAddressByte,
      long value, byte[] data, TransactionExtention.Builder trxExtBuilder,
      Return.Builder retBuilder)
      throws ContractValidateException, ContractExeException, HeaderNotFound, VMIllegalException {

    TriggerSmartContract triggerContract = triggerCallContract(
        ownerAddressByte,
        contractAddressByte,
        value,
        data,
        0,
        null
    );

    TransactionCapsule trxCap = wallet.createTransactionCapsule(triggerContract,
        ContractType.TriggerSmartContract);
    Transaction trx =
        wallet.triggerConstantContract(triggerContract, trxCap, trxExtBuilder, retBuilder);

    trxExtBuilder.setTransaction(trx);
    trxExtBuilder.setTxid(trxCap.getTransactionId().getByteString());
    trxExtBuilder.setResult(retBuilder);
    retBuilder.setResult(true).setCode(response_code.SUCCESS);
  }

  /**
   * @param data Hash of the method signature and encoded parameters. for example:
   * getMethodSign(methodName(uint256,uint256)) || data1 || data2
   */
  private String call(byte[] ownerAddressByte, byte[] contractAddressByte, byte[] data) {

    TransactionExtention.Builder trxExtBuilder = TransactionExtention.newBuilder();
    Return.Builder retBuilder = Return.newBuilder();
    TransactionExtention trxExt;

    try {
      callTriggerConstantContract(ownerAddressByte, contractAddressByte, 0, data,
          trxExtBuilder, retBuilder);

    } catch (ContractValidateException | VMIllegalException e) {
      retBuilder.setResult(false).setCode(response_code.CONTRACT_VALIDATE_ERROR)
          .setMessage(ByteString.copyFromUtf8(CONTRACT_VALIDATE_ERROR + e.getMessage()));
      trxExtBuilder.setResult(retBuilder);
      logger.warn(CONTRACT_VALIDATE_EXCEPTION, e.getMessage());
    } catch (RuntimeException e) {
      retBuilder.setResult(false).setCode(response_code.CONTRACT_EXE_ERROR)
          .setMessage(ByteString.copyFromUtf8(e.getClass() + " : " + e.getMessage()));
      trxExtBuilder.setResult(retBuilder);
      logger.warn("When run constant call in VM, have RuntimeException: " + e.getMessage());
    } catch (Exception e) {
      retBuilder.setResult(false).setCode(response_code.OTHER_ERROR)
          .setMessage(ByteString.copyFromUtf8(e.getClass() + " : " + e.getMessage()));
      trxExtBuilder.setResult(retBuilder);
      logger.warn("Unknown exception caught: " + e.getMessage(), e);
    } finally {
      trxExt = trxExtBuilder.build();
    }

    String result = "0x";
    String code = trxExt.getResult().getCode().toString();
    if ("SUCCESS".equals(code)) {
      List<ByteString> list = trxExt.getConstantResultList();
      byte[] listBytes = new byte[0];
      for (ByteString bs : list) {
        listBytes = ByteUtil.merge(listBytes, bs.toByteArray());
      }
      result = ByteArray.toJsonHex(listBytes);
    } else {
      logger.error("trigger contract failed.");
    }

    return result;
  }

  @Override
  public String getStorageAt(String address, String storageIdx, String blockNumOrTag)
      throws JsonRpcInvalidParamsException {
    if ("earliest".equalsIgnoreCase(blockNumOrTag)
        || "pending".equalsIgnoreCase(blockNumOrTag)) {
      throw new JsonRpcInvalidParamsException("TAG [earliest | pending] not supported");
    } else if ("latest".equalsIgnoreCase(blockNumOrTag)) {
      byte[] addressByte = addressHashToByteArray(address);

      // get contract from contractStore
      BytesMessage.Builder build = BytesMessage.newBuilder();
      BytesMessage bytesMessage = build.setValue(ByteString.copyFrom(addressByte)).build();
      SmartContract smartContract = wallet.getContract(bytesMessage);
      if (smartContract == null) {
        return ByteArray.toJsonHex(new byte[32]);
      }

      StorageRowStore store = manager.getStorageRowStore();
      Storage storage = new Storage(addressByte, store);

      // init Tvm config
      storage.setContractVersion(smartContract.getVersion());
      VMConfig.initAllowTvmCompatibleEvm(1);

      DataWord value = storage.getValue(new DataWord(ByteArray.fromHexString(storageIdx)));
      return ByteArray.toJsonHex(value == null ? new byte[32] : value.getData());
    } else {
      try {
        ByteArray.hexToBigInteger(blockNumOrTag);
      } catch (Exception e) {
        throw new JsonRpcInvalidParamsException("invalid block number");
      }

      throw new JsonRpcInvalidParamsException("QUANTITY not supported, just support TAG as latest");
    }
  }

  @Override
  public String getABIOfSmartContract(String contractAddress, String blockNumOrTag)
      throws JsonRpcInvalidParamsException {
    if ("earliest".equalsIgnoreCase(blockNumOrTag)
        || "pending".equalsIgnoreCase(blockNumOrTag)) {
      throw new JsonRpcInvalidParamsException("TAG [earliest | pending] not supported");
    } else if ("latest".equalsIgnoreCase(blockNumOrTag)) {
      byte[] addressData = addressHashToByteArray(contractAddress);

      BytesMessage.Builder build = BytesMessage.newBuilder();
      BytesMessage bytesMessage = build.setValue(ByteString.copyFrom(addressData)).build();
      SmartContractDataWrapper contractDataWrapper = wallet.getContractInfo(bytesMessage);

      if (contractDataWrapper != null) {
        return ByteArray.toJsonHex(contractDataWrapper.getRuntimecode().toByteArray());
      } else {
        return "0x";
      }

    } else {
      try {
        ByteArray.hexToBigInteger(blockNumOrTag);
      } catch (Exception e) {
        throw new JsonRpcInvalidParamsException("invalid block number");
      }

      throw new JsonRpcInvalidParamsException("QUANTITY not supported, just support TAG as latest");
    }
  }

  @Override
  public String getCoinbase() throws JsonRpcInternalException {
    String address = wallet.getCoinbase();

    if (StringUtils.isEmpty(address)) {
      throw new JsonRpcInternalException("etherbase must be explicitly specified");
    }

    return address;
  }

  // return energy fee
  @Override
  public String gasPrice() {
    return ByteArray.toJsonHex(wallet.getEnergyFee());
  }

  @Override
  public String estimateGas(CallArguments args) throws JsonRpcInvalidRequestException,
      JsonRpcInvalidParamsException, JsonRpcInternalException {
    byte[] ownerAddress = addressHashToByteArray(args.from);

    ContractType contractType = args.getContractType(wallet);
    if (contractType == ContractType.TransferContract) {
      buildTransferContractTransaction(ownerAddress, new BuildArguments(args));
      return "0x0";
    }

    TransactionExtention.Builder trxExtBuilder = TransactionExtention.newBuilder();
    Return.Builder retBuilder = Return.newBuilder();

    try {
      byte[] contractAddress;

      if (contractType == ContractType.TriggerSmartContract) {
        contractAddress = addressHashToByteArray(args.to);
      } else {
        contractAddress = new byte[0];
      }

      callTriggerConstantContract(ownerAddress,
          contractAddress,
          args.parseValue(),
          ByteArray.fromHexString(args.data),
          trxExtBuilder,
          retBuilder);

      return ByteArray.toJsonHex(trxExtBuilder.getEnergyUsed());
    } catch (ContractValidateException e) {
      String errString = "invalid contract";
      if (e.getMessage() != null) {
        errString = e.getMessage();
      }

      throw new JsonRpcInvalidRequestException(errString);
    } catch (Exception e) {
      String errString = "invalid json request";
      if (e.getMessage() != null) {
        errString = e.getMessage().replaceAll("[\"]", "\'");
      }

      throw new JsonRpcInternalException(errString);
    }
  }

  @Override
  public TransactionResult getTransactionByHash(String txId) throws JsonRpcInvalidParamsException {
    ByteString transactionId = ByteString.copyFrom(hashToByteArray(txId));

    TransactionInfo transactionInfo = wallet.getTransactionInfoById(transactionId);
    if (transactionInfo == null) {
      TransactionCapsule transactionCapsule = wallet.getTransactionCapsuleById(transactionId);
      if (transactionCapsule == null) {
        return null;
      }

      BlockCapsule blockCapsule = wallet.getBlockCapsuleByNum(transactionCapsule.getBlockNum());
      if (blockCapsule == null) {
        return new TransactionResult(transactionCapsule.getInstance(), wallet);
      } else {
        int transactionIndex = getTransactionIndex(
            ByteArray.toHexString(transactionCapsule.getTransactionId().getBytes()),
            blockCapsule.getInstance().getTransactionsList());

        if (transactionIndex == -1) {
          return null;
        }

        long energyUsageTotal = 0;
        return new TransactionResult(blockCapsule, transactionIndex,
            transactionCapsule.getInstance(), energyUsageTotal,
            wallet.getEnergyFee(blockCapsule.getTimeStamp()), wallet);
      }
    } else {
      Block block = wallet.getBlockByNum(transactionInfo.getBlockNumber());
      if (block == null) {
        return null;
      }

      return formatTransactionResult(transactionInfo, block);
    }
  }

  private TransactionResult formatTransactionResult(TransactionInfo transactioninfo, Block block) {
    String txId = ByteArray.toHexString(transactioninfo.getId().toByteArray());

    Transaction transaction = null;
    int transactionIndex = -1;

    List<Transaction> txList = block.getTransactionsList();
    for (int index = 0; index < txList.size(); index++) {
      transaction = txList.get(index);
      if (getTxID(transaction).equals(txId)) {
        transactionIndex = index;
        break;
      }
    }

    if (transactionIndex == -1) {
      return null;
    }

    long energyUsageTotal = transactioninfo.getReceipt().getEnergyUsageTotal();
    BlockCapsule blockCapsule = new BlockCapsule(block);
    return new TransactionResult(blockCapsule, transactionIndex, transaction,
        energyUsageTotal, wallet.getEnergyFee(blockCapsule.getTimeStamp()), wallet);
  }

  private TransactionResult getTransactionByBlockAndIndex(Block block, String index)
      throws JsonRpcInvalidParamsException {
    int txIndex;
    try {
      txIndex = ByteArray.jsonHexToInt(index);
    } catch (Exception e) {
      throw new JsonRpcInvalidParamsException("invalid index value");
    }

    if (txIndex >= block.getTransactionsCount()) {
      return null;
    }

    Transaction transaction = block.getTransactions(txIndex);
    long energyUsageTotal = getEnergyUsageTotal(transaction, wallet);
    BlockCapsule blockCapsule = new BlockCapsule(block);

    return new TransactionResult(blockCapsule, txIndex, transaction, energyUsageTotal,
        wallet.getEnergyFee(blockCapsule.getTimeStamp()), wallet);
  }

  @Override
  public TransactionResult getTransactionByBlockHashAndIndex(String blockHash, String index)
      throws JsonRpcInvalidParamsException {
    final Block block = getBlockByJsonHash(blockHash);

    if (block == null) {
      return null;
    }

    return getTransactionByBlockAndIndex(block, index);
  }

  @Override
  public TransactionResult getTransactionByBlockNumberAndIndex(String blockNumOrTag, String index)
      throws JsonRpcInvalidParamsException {
    Block block = wallet.getByJsonBlockId(blockNumOrTag);
    if (block == null) {
      return null;
    }

    return getTransactionByBlockAndIndex(block, index);
  }

  @Override
  public TransactionReceipt getTransactionReceipt(String txId)
      throws JsonRpcInvalidParamsException {
    TransactionInfo transactionInfo =
        wallet.getTransactionInfoById(ByteString.copyFrom(hashToByteArray(txId)));
    if (transactionInfo == null) {
      return null;
    }

    Block block = wallet.getBlockByNum(transactionInfo.getBlockNumber());
    if (block == null) {
      return null;
    }

    return new TransactionReceipt(block, transactionInfo, wallet);
  }

  @Override
  public String getCall(CallArguments transactionCall, String blockNumOrTag)
      throws JsonRpcInvalidParamsException {
    if ("earliest".equalsIgnoreCase(blockNumOrTag)
        || "pending".equalsIgnoreCase(blockNumOrTag)) {
      throw new JsonRpcInvalidParamsException("TAG [earliest | pending] not supported");
    } else if ("latest".equalsIgnoreCase(blockNumOrTag)) {
      byte[] addressData = addressHashToByteArray(transactionCall.from);
      byte[] contractAddressData = addressHashToByteArray(transactionCall.to);

      return call(addressData, contractAddressData, ByteArray.fromHexString(transactionCall.data));
    } else {
      try {
        ByteArray.hexToBigInteger(blockNumOrTag).longValue();
      } catch (Exception e) {
        throw new JsonRpcInvalidParamsException("invalid block number");
      }

      throw new JsonRpcInvalidParamsException("QUANTITY not supported, just support TAG as latest");
    }
  }

  @Override
  public String getPeerCount() {
    // return the peer list count
    return ByteArray.toJsonHex(nodeInfoService.getNodeInfo().getPeerList().size());
  }

  @Override
  public Object getSyncingStatus() {
    if (nodeInfoService.getNodeInfo().getPeerList().isEmpty()) {
      return false;
    }

    long startingBlockNum = nodeInfoService.getNodeInfo().getBeginSyncNum();
    Block nowBlock = wallet.getNowBlock();
    long currentBlockNum = nowBlock.getBlockHeader().getRawData().getNumber();
    long diff = (System.currentTimeMillis()
        - nowBlock.getBlockHeader().getRawData().getTimestamp()) / 3000;
    diff = diff > 0 ? diff : 0;
    long highestBlockNum = currentBlockNum + diff; // estimated the highest block number

    return new SyncingResult(ByteArray.toJsonHex(startingBlockNum),
        ByteArray.toJsonHex(currentBlockNum),
        ByteArray.toJsonHex(highestBlockNum)
    );
  }

  @Override
  public BlockResult getUncleByBlockHashAndIndex(String blockHash, String index) {
    return null;
  }

  @Override
  public BlockResult getUncleByBlockNumberAndIndex(String blockNumOrTag, String index) {
    return null;
  }

  @Override
  public String getUncleCountByBlockHash(String blockHash) {
    return "0x0";
  }

  @Override
  public String getUncleCountByBlockNumber(String blockNumOrTag) {
    return "0x0";
  }

  @Override
  public List<Object> ethGetWork() {
    Block block = wallet.getNowBlock();
    String blockHash = null;

    if (block != null) {
      blockHash = ByteArray.toJsonHex(new BlockCapsule(block).getBlockId().getBytes());
    }

    return Arrays.asList(
        blockHash,
        null,
        null
    );
  }

  @Override
  public String getHashRate() {
    return "0x0";
  }

  @Override
  public boolean isMining() {
    return wallet.isMining();
  }

  @Override
  public String[] getAccounts() {
    return new String[0];
  }

  private TransactionJson buildCreateSmartContractTransaction(byte[] ownerAddress,
      BuildArguments args) throws JsonRpcInvalidParamsException, JsonRpcInvalidRequestException,
      JsonRpcInternalException {
    try {
      CreateSmartContract.Builder build = CreateSmartContract.newBuilder();

      build.setOwnerAddress(ByteString.copyFrom(ownerAddress));

      build.setCallTokenValue(args.tokenValue)
          .setTokenId(args.tokenId);

      ABI.Builder abiBuilder = ABI.newBuilder();
      if (StringUtils.isNotEmpty(args.abi)) {
        String abiStr = "{" + "\"entrys\":" + args.abi + "}";
        JsonFormat.merge(abiStr, abiBuilder, args.visible);
      }

      SmartContract.Builder smartBuilder = SmartContract.newBuilder();
      smartBuilder
          .setAbi(abiBuilder)
          .setCallValue(args.parseValue())
          .setConsumeUserResourcePercent(args.consumeUserResourcePercent)
          .setOriginEnergyLimit(args.originEnergyLimit);

      smartBuilder.setOriginAddress(ByteString.copyFrom(ownerAddress));

      // bytecode + parameter
      smartBuilder.setBytecode(ByteString.copyFrom(ByteArray.fromHexString(args.data)));

      if (StringUtils.isNotEmpty(args.name)) {
        smartBuilder.setName(args.name);
      }

      build.setNewContract(smartBuilder);

      Transaction tx = wallet
          .createTransactionCapsule(build.build(), ContractType.CreateSmartContract).getInstance();
      Transaction.Builder txBuilder = tx.toBuilder();
      Transaction.raw.Builder rawBuilder = tx.getRawData().toBuilder();
      rawBuilder.setFeeLimit(args.parseGas() * wallet.getEnergyFee());

      txBuilder.setRawData(rawBuilder);
      tx = setTransactionPermissionId(args.permissionId, txBuilder.build());

      TransactionJson transactionJson = new TransactionJson();
      transactionJson.transaction = JSON.parseObject(Util.printCreateTransaction(tx, false));

      return transactionJson;
    } catch (JsonRpcInvalidParamsException e) {
      throw new JsonRpcInvalidParamsException(e.getMessage());
    } catch (ContractValidateException e) {
      throw new JsonRpcInvalidRequestException(e.getMessage());
    } catch (Exception e) {
      throw new JsonRpcInternalException(e.getMessage());
    }
  }

  // from and to should not be null
  private TransactionJson buildTriggerSmartContractTransaction(byte[] ownerAddress,
      BuildArguments args) throws JsonRpcInvalidParamsException, JsonRpcInvalidRequestException,
      JsonRpcInternalException {
    byte[] contractAddress = addressHashToByteArray(args.to);

    TriggerSmartContract.Builder build = TriggerSmartContract.newBuilder();
    TransactionExtention.Builder trxExtBuilder = TransactionExtention.newBuilder();
    Return.Builder retBuilder = Return.newBuilder();

    try {

      build.setOwnerAddress(ByteString.copyFrom(ownerAddress))
          .setContractAddress(ByteString.copyFrom(contractAddress));

      if (StringUtils.isNotEmpty(args.data)) {
        build.setData(ByteString.copyFrom(ByteArray.fromHexString(args.data)));
      } else {
        build.setData(ByteString.copyFrom(new byte[0]));
      }

      build.setCallTokenValue(args.tokenValue)
          .setTokenId(args.tokenId)
          .setCallValue(args.parseValue());

      Transaction tx = wallet
          .createTransactionCapsule(build.build(), ContractType.TriggerSmartContract).getInstance();

      Transaction.Builder txBuilder = tx.toBuilder();
      Transaction.raw.Builder rawBuilder = tx.getRawData().toBuilder();
      rawBuilder.setFeeLimit(args.parseGas() * wallet.getEnergyFee());
      txBuilder.setRawData(rawBuilder);

      Transaction trx = wallet
          .triggerContract(build.build(), new TransactionCapsule(txBuilder.build()), trxExtBuilder,
              retBuilder);
      trx = setTransactionPermissionId(args.permissionId, trx);
      trxExtBuilder.setTransaction(trx);
    } catch (JsonRpcInvalidParamsException e) {
      throw new JsonRpcInvalidParamsException(e.getMessage());
    } catch (ContractValidateException e) {
      throw new JsonRpcInvalidRequestException(e.getMessage());
    } catch (Exception e) {
      String errString = "invalid json request";
      if (e.getMessage() != null) {
        errString = e.getMessage().replaceAll("[\"]", "\'");
      }

      throw new JsonRpcInternalException(errString);
    }

    String jsonString = Util.printTransaction(trxExtBuilder.build().getTransaction(), args.visible);
    TransactionJson transactionJson = new TransactionJson();
    transactionJson.transaction = JSON.parseObject(jsonString);

    return transactionJson;
  }

  private TransactionJson createTransactionJson(GeneratedMessageV3.Builder<?> build,
      ContractType contractTyp, BuildArguments args)
      throws JsonRpcInvalidRequestException, JsonRpcInternalException {
    try {
      Transaction tx = wallet
          .createTransactionCapsule(build.build(), contractTyp)
          .getInstance();
      tx = setTransactionPermissionId(args.permissionId, tx);
      tx = setTransactionExtraData(args.extraData, tx, args.visible);

      String jsonString = Util.printCreateTransaction(tx, args.visible);

      TransactionJson transactionJson = new TransactionJson();
      transactionJson.transaction = JSON.parseObject(jsonString);

      return transactionJson;
    } catch (ContractValidateException e) {
      throw new JsonRpcInvalidRequestException(e.getMessage());
    } catch (Exception e) {
      throw new JsonRpcInternalException(e.getMessage());
    }
  }

  private TransactionJson buildTransferContractTransaction(byte[] ownerAddress,
      BuildArguments args) throws JsonRpcInvalidParamsException, JsonRpcInvalidRequestException,
      JsonRpcInternalException {
    long amount = args.parseValue();

    TransferContract.Builder build = TransferContract.newBuilder();
    build.setOwnerAddress(ByteString.copyFrom(ownerAddress))
        .setToAddress(ByteString.copyFrom(addressHashToByteArray(args.to)))
        .setAmount(amount);

    return createTransactionJson(build, ContractType.TransferContract, args);
  }

  // tokenId and tokenValue should not be null
  private TransactionJson buildTransferAssetContractTransaction(byte[] ownerAddress,
      BuildArguments args) throws JsonRpcInvalidParamsException, JsonRpcInvalidRequestException,
      JsonRpcInternalException {
    byte[] tokenIdArr = ByteArray.fromString(String.valueOf(args.tokenId));
    if (tokenIdArr == null) {
      throw new JsonRpcInvalidParamsException("invalid param value: invalid tokenId");
    }

    TransferAssetContract.Builder build = TransferAssetContract.newBuilder();
    build.setOwnerAddress(ByteString.copyFrom(ownerAddress))
        .setToAddress(ByteString.copyFrom(addressHashToByteArray(args.to)))
        .setAssetName(ByteString.copyFrom(tokenIdArr))
        .setAmount(args.tokenValue);

    return createTransactionJson(build, ContractType.TransferAssetContract, args);
  }

  public RequestSource getSource() {
    Chainbase.Cursor cursor = wallet.getCursor();
    switch (cursor) {
      case SOLIDITY:
        return RequestSource.SOLIDITY;
      case PBFT:
        return RequestSource.PBFT;
      default:
        return RequestSource.FULLNODE;
    }
  }

  @Override
  public TransactionJson buildTransaction(BuildArguments args)
      throws JsonRpcInvalidParamsException, JsonRpcInvalidRequestException,
      JsonRpcInternalException, JsonRpcMethodNotFoundException {

    if (getSource() != RequestSource.FULLNODE) {
      String msg = String
          .format("the method buildTransaction does not exist/is not available in %s",
              getSource().toString());
      throw new JsonRpcMethodNotFoundException(msg);
    }

    byte[] fromAddressData;
    try {
      fromAddressData = addressHashToByteArray(args.from);
    } catch (JsonRpcInvalidParamsException e) {
      throw new JsonRpcInvalidRequestException("invalid json request");
    }

    // check possible ContractType
    ContractType contractType = args.getContractType(wallet);
    switch (contractType.getNumber()) {
      case ContractType.CreateSmartContract_VALUE:
        return buildCreateSmartContractTransaction(fromAddressData, args);
      case ContractType.TriggerSmartContract_VALUE:
        return buildTriggerSmartContractTransaction(fromAddressData, args);
      case ContractType.TransferContract_VALUE:
        return buildTransferContractTransaction(fromAddressData, args);
      case ContractType.TransferAssetContract_VALUE:
        return buildTransferAssetContractTransaction(fromAddressData, args);
      default:
        break;
    }

    return null;
  }

  @Override
  public boolean ethSubmitWork(String nonceHex, String headerHex, String digestHex)
      throws JsonRpcMethodNotFoundException {
    throw new JsonRpcMethodNotFoundException(
        "the method eth_submitWork does not exist/is not available");
  }

  @Override
  public String ethSendRawTransaction(String rawData) throws JsonRpcMethodNotFoundException {
    throw new JsonRpcMethodNotFoundException(
        "the method eth_sendRawTransaction does not exist/is not available");
  }

  @Override
  public String ethSendTransaction(CallArguments args) throws JsonRpcMethodNotFoundException {
    throw new JsonRpcMethodNotFoundException(
        "the method eth_sendTransaction does not exist/is not available");
  }

  @Override
  public String ethSign(String address, String msg) throws JsonRpcMethodNotFoundException {
    throw new JsonRpcMethodNotFoundException(
        "the method eth_sign does not exist/is not available");
  }

  @Override
  public String ethSignTransaction(CallArguments transactionArgs)
      throws JsonRpcMethodNotFoundException {
    throw new JsonRpcMethodNotFoundException(
        "the method eth_signTransaction does not exist/is not available");
  }

  @Override
  public String parityNextNonce(String address) throws JsonRpcMethodNotFoundException {
    throw new JsonRpcMethodNotFoundException(
        "the method parity_nextNonce does not exist/is not available");
  }

  @Override
  public String getSendTransactionCountOfAddress(String address, String blockNumOrTag)
      throws JsonRpcMethodNotFoundException {
    throw new JsonRpcMethodNotFoundException(
        "the method eth_getTransactionCount does not exist/is not available");
  }

  @Override
  public String[] getCompilers() throws JsonRpcMethodNotFoundException {
    throw new JsonRpcMethodNotFoundException(
        "the method eth_getCompilers does not exist/is not available");
  }

  @Override
  public CompilationResult ethCompileSolidity(String contract)
      throws JsonRpcMethodNotFoundException {
    throw new JsonRpcMethodNotFoundException(
        "the method eth_compileSolidity does not exist/is not available");
  }

  @Override
  public CompilationResult ethCompileLLL(String contract) throws JsonRpcMethodNotFoundException {
    throw new JsonRpcMethodNotFoundException(
        "the method eth_compileLLL does not exist/is not available");
  }

  @Override
  public CompilationResult ethCompileSerpent(String contract)
      throws JsonRpcMethodNotFoundException {
    throw new JsonRpcMethodNotFoundException(
        "the method eth_compileSerpent does not exist/is not available");
  }

  @Override
  public CompilationResult ethSubmitHashrate(String hashrate, String id)
      throws JsonRpcMethodNotFoundException {
    throw new JsonRpcMethodNotFoundException(
        "the method eth_submitHashrate does not exist/is not available");
  }
}
