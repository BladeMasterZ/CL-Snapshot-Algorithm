package pit;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import javax.jms.*;
import javax.naming.*;

/*
 * This is the ONLY file that you are to edit.  It is the model of play for
 * every PITplayer.  Each PITplayer instantiates this model and uses it to
 * process the messages it receives.
 */
public class PITPlayerModel {

    // Each PITplayer has a unique myPlayerNumber.  It is set in the PITPlayer constructor.
    private final int myPlayerNumber;
    // Cards is this player's set of cards.  
    private final ArrayList cards = new ArrayList();
    // numTrades counts trades.
    private int numTrades = 0;
    // maxTrades is the maximum number of trades, after which trading is stopped.
    private final int maxTrades = 20000;
    // numPlayers are the number of Players trading.  This comes with a NewHand from the PITsnapshot servlet
    private int numPlayers = 0;
    // halting indicates that the system is being reset, so ignore trades unti a new had received.
    private boolean halting = false;

    /* The snapshot servlet (PITsnapshot) is expecting to be passed an ObjectMessage
     * where the object is a HashMap. Therefore this definition of that HashMap is 
     * provided although it is not currently used (it is for you to use).
     * PITsnapshot is expecting a set of attibute/value pairs.  These include the player
     * number, as in state.put("Player", myPlayerNumber), and each commodity string
     * and the number of that commodity in the snapshot.
     * Also included below is a utility method that will convert a HashMap into a string
     * which is useful for printing diagnostic messages to the console.
     */
    private HashMap<String, Integer> state;

    // PITPlayerModel constructor saves what number player this object represents.
    PITPlayerModel(int myNumber) {
        myPlayerNumber = myNumber;
    }

    public void onMessage(Message message) {
        try {
            if (message instanceof ObjectMessage) {
                Object o = ((ObjectMessage) message).getObject();

                /*
                 * There are 6 types of messages:  Reset, NewHand, TenderOffer,
                 * AcceptOffer, RejectOffer, and Marker
                 */
                
                // Reset the Player.  This message is generated by the PITsnapshot servlet
                if (o instanceof Reset) {
                    doReset((Reset) o);

                // NewHand received from PITsnapshot
                } else if (o instanceof NewHand) {
                    // Add the new hand into cards
                    doNewHand((NewHand) o);

                // Receive an offer from another Player
                } else if (o instanceof TenderOffer) {
                    doReceiveTenderOffer((TenderOffer) o);

                // Another Player accepted our offer
                } else if (o instanceof AcceptOffer) {
                    doReceiveAcceptOffer((AcceptOffer) o);

                // Another Player rejected our offer
                } else if (o instanceof RejectOffer) {
                    doReceiveRejectOffer((RejectOffer) o);

                } else {
                    System.out.println("PITplayer" + myPlayerNumber + " received unknown Message type");
                    // just ignore it
                }
            }
        } catch (Exception e) {
            System.out.println("Exception thrown in PITplayer" + myPlayerNumber + ": " + e);
        }
    }

    private void doReset(Reset reset) throws Exception {
        // Resetting is done by two messages, first to halt, then to clear
        if (reset.action == Reset.HALT) {
            System.out.println("PITplayer" + myPlayerNumber + " received Reset HALT");
            halting = true;
            // Reply to the PITsnapshot servlet acknowledging the Reset HALT
            sendToQueue("jms/PITmonitor", reset);
        } else { // action == Reset.CLEAR
            System.out.println("PITplayer" + myPlayerNumber + " received Reset RESET");
            // Drop all cards in hand
            cards.clear();
            numTrades = 0;
            numPlayers = 0;
            halting = false;
            // Reply to the PITsnapshot servlet acknowledging the Reset
            sendToQueue("jms/PITmonitor", reset);
        }
    }

    private void doNewHand(NewHand hand) throws Exception {
        // Add a new hand of cards.  
        // It is actually possible that an offer from another Player has been
        // accepted already, beating the NewHand
        cards.addAll((hand).newHand);
        numPlayers = (hand).numPlayers;
        System.out.println("PITplayer" + myPlayerNumber + " new hand: " + toString(cards));
        // Offer a card to another Player
        doTenderOffer();
    }

    private void doReceiveTenderOffer(TenderOffer trade) throws Exception {
        if (halting) {
            return; // if halting, discard trade
        }

        System.out.println("PITplayer" + myPlayerNumber + " received offer of: " + trade.tradeCard + " from player: " + trade.sourcePlayer);

        // When receiving an offer, decide whether to Accept or Reject it

        if (Math.random() < 0.8) {
            // Accept the trade 80% of the time

            // Add the Offer to my hand of cards
            cards.add(trade.tradeCard);
            // Pay with one of my cards
            doReplyAccept(trade.sourcePlayer);

        } else {
            /* Otherwise reject the offer and send back the card */
            doReplyReject(trade);
        }

    }

    private void doReplyAccept(int sendTo) throws Exception {

        // if hit maxTrades limit, then stop sending trades
        if (maxTrades(maxTrades)) {
            return;
        }

        // In payment for the card I just accepted, send back one of my cards.
        AcceptOffer newTrade = new AcceptOffer();
        newTrade.tradeCard = (String) cards.remove(0);
        newTrade.sourcePlayer = myPlayerNumber;

        //Send the card to the other player
        System.out.println("PITplayer" + myPlayerNumber + " accepting offer and paying with: " + newTrade.tradeCard + " to player: " + sendTo);
        System.out.println("PITplayer" + myPlayerNumber + " hand: " + toString(cards));
        String sendToJNDI = "jms/PITplayer" + sendTo;
        sendToQueue(sendToJNDI, newTrade);
    }

    // Reply rejecting an offer that was received.  Send back their card.
    private void doReplyReject(TenderOffer trade) throws Exception {
        if (halting) {
            return; // if halting, discard trade
        }

        System.out.println("PITplayer" + myPlayerNumber + " rejecting offer of: " + trade.tradeCard + " from player: " + trade.sourcePlayer);
        System.out.println("PITplayer" + myPlayerNumber + " hand: " + toString(cards));

        // if hit maxTrades limit, then stop sending trades
        if (maxTrades(maxTrades)) {
            return;
        }

        // Send back their card that I am rejecting
        RejectOffer newTrade = new RejectOffer();
        newTrade.tradeCard = trade.tradeCard;
        newTrade.sourcePlayer = myPlayerNumber;

        //Send the card to the other player
        String sendToJNDI = "jms/PITplayer" + trade.sourcePlayer;
        sendToQueue(sendToJNDI, newTrade);

    }

    // Handle receiving a message that a previous offer has been accepted.
    // They would have replied with another card as payment.
    private void doReceiveAcceptOffer(AcceptOffer trade) throws Exception {
        if (halting) {
            return; // if halting, discard trade
        }
        // Having received a AcceptOffer from another Player, add it to my hand of cards
        cards.add(trade.tradeCard);

        System.out.println("PITplayer" + myPlayerNumber + " received: " + trade.tradeCard + " as payment from player: " + trade.sourcePlayer);
        System.out.println("PITplayer" + myPlayerNumber + " hand: " + toString(cards));
        // Make another offer to a random player
        doTenderOffer();
    }

    // Handle receiving a reject message regarding a prior offer I made
    private void doReceiveRejectOffer(RejectOffer trade) throws Exception {
        if (halting) {
            return; // if halting, discard trade
        }
        // Because the offer was rejected, and returned, add it back into my cards
        cards.add(trade.tradeCard);

        System.out.println("PITplayer" + myPlayerNumber + " received rejected offer of: " + trade.tradeCard + " from player: " + trade.sourcePlayer);
        System.out.println("PITplayer" + myPlayerNumber + " hand: " + toString(cards));
        // Make another offer to a random player
        doTenderOffer();
    }

    // Make an offer to a random player
    private void doTenderOffer() throws Exception {

        // if hit maxTrades limit, then stop sending trades
        if (maxTrades(maxTrades)) {
            return;
        }

        /*
         * If numPlayers == 0, while we have received a TenderOffer, we have not 
         * received our NewHand yet, so we don't know how many players there 
         * are.  Therefore, don't send out a TenderOffer at this time.
         * 
         */
        if (numPlayers == 0) {
            return;
        }

        // Create a new offer from my set of cards, and send to another player
        TenderOffer newTrade = new TenderOffer();
        newTrade.tradeCard = (String) cards.remove(0);
        newTrade.sourcePlayer = myPlayerNumber;

        // Find a random player to trade to (not including myself)
        int sendTo = myPlayerNumber;
        while (sendTo == myPlayerNumber) {
            sendTo = Math.round((float) Math.random() * (numPlayers - 1));
        }

        //Send the card to the other player
        System.out.println("PITplayer" + myPlayerNumber + " offered: " + newTrade.tradeCard + " to player: " + sendTo);
        String sendToJNDI = "jms/PITplayer" + sendTo;
        sendToQueue(sendToJNDI, newTrade);

    }

    // Create a string of hand size and all cards
    private String toString(ArrayList hand) {

        String cardsString = "size: " + hand.size() + " ";
        for (int i = 0; i < hand.size(); i++) {
            cardsString += hand.get(i) + " ";
        }
        return cardsString;
    }

    // Create a printable version of the "state".
    private String toString(HashMap<String, Integer> state) {
        String stateString = "";
        for (Iterator it = state.entrySet().iterator(); it.hasNext();) {
            Map.Entry entry = (Map.Entry) it.next();
            String commodity = (String) entry.getKey();
            int number = ((Integer) entry.getValue()).intValue();
            stateString += "{" + commodity + ":" + number + "} ";
        }
        return stateString;
    }

    // Send an object to a Queue, given its JNDI name
    private void sendToQueue(String queueJNDI, Serializable message) throws Exception {
        // Gather necessary JMS resources
        Context ctxt = new InitialContext();
        Connection con = ((ConnectionFactory) ctxt.lookup("jms/myConnectionFactory")).createConnection();
        Session session = con.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Queue q = (Queue) ctxt.lookup(queueJNDI);
        MessageProducer writer = session.createProducer(q);
        ObjectMessage msg = session.createObjectMessage(message);
        // Send the object to the Queue
        writer.send(msg);
        session.close();
        con.close();
        ctxt.close();
    }

    // Stop trading when the max number of Trades is reached
    private boolean maxTrades(int max) {
        if ((numTrades % 100) == 0) {
            System.out.println("PITplayer" + myPlayerNumber + " tradeCount: " + numTrades);
        }
        return (numTrades++ < max) ? false : true;
    }
}
