package btdex.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collection;
import java.util.HashMap;

import org.bouncycastle.util.encoders.Hex;

import bt.compiler.Compiler;
import btdex.sc.BuyContract;
import btdex.sc.SellContract;
import btdex.sc.SellNoDepositContract;
import burst.kit.entity.BurstAddress;
import burst.kit.entity.BurstID;
import burst.kit.entity.response.AT;
import burst.kit.entity.response.Block;
import burst.kit.entity.response.Transaction;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Contracts {
    private static Compiler compilerSell, compilerNoDeposit, compilerBuy;

    private static String contractTakeHash, contractBuyTakeHash;

	private static HashMap<BurstAddress, ContractState> contractsMap = new HashMap<>();
	private static boolean loading = true;
	private static BurstID mostRecentID;
	private static BurstID lastBlock;
	private static ContractState freeContract, freeNoDepositContract, freeBuyContract;

	private static Logger logger = LogManager.getLogger();

	static class UpdateThread extends Thread {
		@Override
		public void run() {
			while(!Thread.currentThread().isInterrupted()) {
				updateContracts();
				loading = false;
				try {
					Thread.sleep(4000);
				} catch (InterruptedException e) {
					logger.error("InterruptedException {}", e.getLocalizedMessage());
					break;
				}
			}
		}
	}

	public static boolean isLoading() {
		return loading;
	}

    static {
        try {
            compilerSell = new Compiler(SellContract.class);
            compilerSell.compile();
            compilerSell.link();

            compilerNoDeposit = new Compiler(SellNoDepositContract.class);
            compilerNoDeposit.compile();
            compilerNoDeposit.link();

            compilerBuy = new Compiler(BuyContract.class);
            compilerBuy.compile();
            compilerBuy.link();

            // get the update method hash
        	ByteBuffer b = ByteBuffer.allocate(8);
            b.order(ByteOrder.LITTLE_ENDIAN);
            b.putLong(compilerSell.getMethod("take").getHash());
            contractTakeHash = Hex.toHexString(b.array());

            b.clear();
            b.putLong(compilerBuy.getMethod("take").getHash());
            contractBuyTakeHash = Hex.toHexString(b.array());

            // TODO: remove this condition on the future
            if(Globals.getInstance().isTestnet()) {
            	// start the update thread
            	new UpdateThread().start();
            }
        } catch (IOException e) {
        	logger.error("IOException: {}", e.getLocalizedMessage());
            e.printStackTrace();
        }
    }

    public static Compiler getCompiler(ContractType type) {
    	switch (type) {
		case BUY:
			return compilerBuy;
		case NO_DEPOSIT:
			return compilerNoDeposit;
		default:
		}
        return compilerSell;
    }

    public static Compiler getContractNoDeposit() {
        return compilerNoDeposit;
    }

    public static Compiler getContractBuy() {
        return compilerBuy;
    }

    public static byte[] getCodeSell() {
        return compilerSell.getCode();
    }

    public static byte[] getCodeNoDeposit() {
        return compilerNoDeposit.getCode();
    }

    public static byte[] getCodeBuy() {
        return compilerBuy.getCode();
    }

    public static String getContractTakeHash(ContractType type) {
    	if(type == ContractType.BUY)
    		return contractBuyTakeHash;
    	return contractTakeHash;
    }

	public static boolean checkContractCode(AT at, byte []code) {
		if(at.getMachineCode().length < code.length) {
			logger.warn("AT code {} less then {}",at.getMachineCode().length, code.length);
			return false;
		}

		for (int i = 0; i < code.length; i++) {
			if(at.getMachineCode()[i] != code[i]){
				logger.warn("AT code do not match");
				return false;
			}
		}

		// TODO, also check the creation transaction and the initial data
		return true;
	}

	public static Collection<ContractState> getContracts() {
		return contractsMap.values();
	}

	private static void updateContracts() {
		// check for new contracts and add them to the list
		mostRecentID = ContractState.addContracts(contractsMap, mostRecentID);

		Globals g = Globals.getInstance();

		// check if we have a new block or not
		Block[] latestBlocks = g.getNS().getBlocks(0, 1).blockingGet();
		boolean noNewBlock = latestBlocks[0].getId().equals(lastBlock);

		ContractState updatedFreeContract = null;
		ContractState updatedBuyFreeContract = null;
		ContractState updatedFreeNoDepositContract = null;

		// check for the pending transactions
		Transaction[] utxs = g.getNS().getUnconfirmedTransactions(g.getAddress()).blockingGet();

		// update the state of every contract
		for(ContractState s : contractsMap.values()) {
			s.update(utxs, noNewBlock);

			if(s.getType() == ContractType.SELL &&
					s.getCreator().equals(g.getAddress()) &&
					s.getState() == SellContract.STATE_FINISHED && !s.hasPending() &&
					g.getMediators().areMediatorsAccepted(s) &&
					s.getATVersion()>1)
				updatedFreeContract = s;
			else if(s.getType() == ContractType.BUY &&
					s.getCreator().equals(g.getAddress()) &&
					s.getState() == SellContract.STATE_FINISHED && !s.hasPending() &&
					g.getMediators().areMediatorsAccepted(s) &&
					s.getATVersion()>1)
				updatedBuyFreeContract = s;
			else if(s.getType() == ContractType.NO_DEPOSIT &&
					s.getCreator().equals(g.getAddress()) &&
					s.getState() == SellNoDepositContract.STATE_FINISHED && !s.hasPending() &&
					g.getMediators().areMediatorsAccepted(s) &&
					s.getATVersion()>1)
				updatedFreeNoDepositContract = s;
		}
		lastBlock = latestBlocks[0].getId();

		// TODO: maybe a lock around this
		freeContract = updatedFreeContract;
		freeBuyContract = updatedBuyFreeContract;
		freeNoDepositContract = updatedFreeNoDepositContract;
	}

	public static long[] getNewContractData() {
		Mediators mediators = Globals.getInstance().getMediators();
		BurstID[] med = mediators.getTwoRandomMediators();

		long data[] = new long[3];
		data[0] = Globals.getInstance().getFeeContract();

		data[1] = med[0].getSignedLongId();
		data[2] = med[1].getSignedLongId();

		return data;
	}

	public static ContractState getFreeContract() {
		return freeContract;
	}

	public static ContractState getFreeBuyContract() {
		return freeBuyContract;
	}

	public static ContractState getFreeNoDepositContract() {
		return freeNoDepositContract;
	}
}
