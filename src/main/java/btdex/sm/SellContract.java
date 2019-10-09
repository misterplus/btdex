package btdex.sm;

import bt.Contract;
import bt.Address;
import bt.Timestamp;
import bt.ui.EmulatorWindow;

/**
 * BlockTalk on-chain decentralized exchange smart contract for selling BURST.
 * 
 * Someone willing to sell BURST should create a contract instance and configure
 * it accordingly. There is zero fee for those selling BURST and 0.25% fee for
 * the buyer (taker). Smart contract activation fees as well as transaction
 * network (mining) fees also apply.
 * 
 * Eventual trade disputes are handled by an arbitrator system. Fees go to a
 * smart contract account, distributed to BTDEX token holders monthly.
 * BTDEX, in turn, is distributed as trade rewards, for trade offer makers
 * and arbitrators.
 * 
 * @author jjos
 */
public class SellContract extends Contract {

	public static final long ACTIVATION_FEE = 28 * ONE_BURST;

	public static final long STATE_FINISHED = 0x0000000000000000;
	public static final long STATE_OPEN = 0x0000000000000001;
	public static final long STATE_PAUSED = 0x0000000000000002;

	public static final long STATE_TAKEN = 0x0000000000000010;
	public static final long STATE_WAITING_PAYMT = 0x0000000000000020;
	public static final long STATE_PAYMT_REPORTED = 0x0000000000000040;

	public static final long STATE_DISPUTE = 0x0000000000000100;
	public static final long STATE_CREATOR_DISPUTE = 0x0000000000000200;
	public static final long STATE_TAKER_DISPUTE = 0x0000000000000400;

	/** Address of the contract collecting the fees to be distributed to BTDEX holders */
	Address feeContract;
	
	Address arbitrator1;
	Address arbitrator2;
	long offerType;
	long accountHash;

	long state;
	long rate;
	Timestamp pauseTimeout;
	long amount;
	long security;
	long nonce;

	Address taker;

	/**
	 * Update the offer settings, must be called by the creator.
	 * 
	 * With this method the creator can update the rate, security deposit, and pause
	 * timeout. The offer status is also changed to **open** if the given timeout is
	 * greater than zero. If the creator sets the security deposit to zero, the offer
	 * is withdrawn and all balance is send back to the creator.
	 * 
	 * @param rate         the exchange rate per BURST
	 * @param security     the security deposit of this offer (in planck)
	 * @param pauseTimeout number of minutes this order should be open
	 */
	public void update(long rate, long security, long pauseTimeout) {
		if (getCurrentTxSender() == getCreator() && state < STATE_TAKEN) {
			// is the creator and the order should not be taken for us to change it
			this.state = STATE_PAUSED;
			this.rate = rate;
			this.security = security;
			nonce++;
			if (security == 0) {
				// withdraw, taking any security deposit balance
				amount = 0;
				sendBalance(getCreator());
				return;
			}
			
			if (pauseTimeout > 0)
				this.state = STATE_OPEN;
			
			this.pauseTimeout = getCurrentTxTimestamp().addMinutes(pauseTimeout);

			// Amount being sold is the current balance, minus the security.
			// Seller can loose the security deposit if not respecting the protocol
			amount = getCurrentBalance() - security;
		}
	}

	/**
	 * Take an open offer by sending the expected security deposit.
	 * 
	 * The order is actually taken only if the given rate and security values match
	 * those currently valid for this contract. Besides, the amount along with
	 * this transaction should be higher or equal the security deposit required.
	 * If any of these conditions is not met, the order is not taken and the
	 * amount sent is refunded (minus the activation fee).
	 * 
	 * @param rate         the exchange rate per BURST
	 * @param security     the security deposit of this offer (in planck)
	 * @param amount       the amount of this offer (in planck)
	 */
	public void take(long rate, long security, long amount) {
		if (getCurrentTxTimestamp().ge(this.pauseTimeout)) {
			// offer has timed out
			state = STATE_PAUSED;
		}
		if (state != STATE_OPEN || this.rate != rate || this.security != security
				|| this.amount != amount
				|| getCurrentTxAmount() + ACTIVATION_FEE < security) {
			// if it is not open or values do no match, refund
			sendAmount(getCurrentTxAmount(), getCurrentTxSender());
			return;
		}

		// All fine, let's take this offer.
		// Taker can loose his security deposit if not respecting the protocol
		state = STATE_WAITING_PAYMT;
		taker = getCurrentTxSender();
	}	

	/**
	 * Report that the payment was received and then send the BURST amount to taker.
	 * 
	 * This method also return the respective security deposits as well as pays the
	 * fee.
	 */
	public void reportComplete() {
		if (getCurrentTxSender() == getCreator() && state >= STATE_WAITING_PAYMT) {
			// Only the creator can report the process is complete

			// creator gets back the security deposity (zero fee)
			sendAmount(security, getCreator());

			// taker gets the amount plus his security minus the 0.25% fee
			sendAmount(amount + security - amount/400, taker);
			taker = null;
			state = STATE_FINISHED;

			// the fee comes from the balance
			sendBalance(feeContract);
		}
	}

	/**
	 * Opens a dispute by taker or creator.
	 */
	public void openDispute() {
		if (state >= STATE_WAITING_PAYMT && getCurrentTxSender() == getCreator()) {
			state += STATE_CREATOR_DISPUTE;
			return;
		}
		if (state >= STATE_WAITING_PAYMT && getCurrentTxSender() == taker) {
			state += STATE_TAKER_DISPUTE;
		}
	}

	/**
	 * Closes the dispute sending the given amounts to creator and taker and the
	 * rest taken as dispute fee.
	 * 
	 * Only an arbitrator of this contract can close a dispute.
	 * 
	 * @param amountToCreator
	 * @param amountToTaker
	 */
	public void closeDispute(long amountToCreator, long amountToTaker) {
		if (state >= STATE_DISPUTE && (getCurrentTxSender() == arbitrator1 || getCurrentTxSender() == arbitrator2)) {
			sendAmount(amountToCreator, getCreator());
			sendAmount(amountToTaker, taker);
			taker = null;
			state = STATE_FINISHED;

			// dispute fee is sent to the fee contract
			sendBalance(feeContract);
		}
	}

	@Override
	public void txReceived() {
		// We ignore any messages other than the above functions, refund any accidental
		// transaction to avoid complaints
		sendAmount(getCurrentTxAmount(), getCurrentTxSender());
	}

	public static void main(String[] args) {
		new EmulatorWindow(SellContract.class);
	}
}
