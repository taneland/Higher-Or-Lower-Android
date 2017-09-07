

package com.example.fredriktaneland.project;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.games.Games;
import com.google.android.gms.games.GamesActivityResultCodes;
import com.google.android.gms.games.GamesStatusCodes;
import com.google.android.gms.games.multiplayer.Invitation;
import com.google.android.gms.games.multiplayer.Multiplayer;
import com.google.android.gms.games.multiplayer.OnInvitationReceivedListener;
import com.google.android.gms.games.multiplayer.Participant;
import com.google.android.gms.games.multiplayer.realtime.RealTimeMessage;
import com.google.android.gms.games.multiplayer.realtime.RealTimeMessageReceivedListener;
import com.google.android.gms.games.multiplayer.realtime.Room;
import com.google.android.gms.games.multiplayer.realtime.RoomConfig;
import com.google.android.gms.games.multiplayer.realtime.RoomStatusUpdateListener;
import com.google.android.gms.games.multiplayer.realtime.RoomUpdateListener;
import com.google.example.games.basegameutils.BaseGameUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static android.provider.BaseColumns._ID;
import static com.example.fredriktaneland.project.Constants.TABLE_NAME;
import static com.example.fredriktaneland.project.Constants.TIME;
import static com.example.fredriktaneland.project.Constants.TITLE;

/**
 * @author Fredrik Tåneland, Johannes Haglund, 2017-01-12
 *
 * Higher or Lower the game
 *
 */
public class MainActivity extends Activity
        implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener,
        View.OnClickListener, RealTimeMessageReceivedListener,
        RoomStatusUpdateListener, RoomUpdateListener, OnInvitationReceivedListener {

    /*
     * Allt som har med kopplingen till Google play services
     */

    final static String TAG = "HigherOrLower";
    final static int RC_SELECT_PLAYERS = 10000;
    final static int RC_INVITATION_INBOX = 10001;
    final static int RC_WAITING_ROOM = 10002;

    private static final int RC_SIGN_IN = 9001;
    private GoogleApiClient mGoogleApiClient;
    private boolean mResolvingConnectionFailure = false;
    private boolean mSignInClicked = false;
    private boolean mAutoStartSignInFlow = true;
    boolean mMultiplayer = false;
    byte[] mMsgBuf = new byte[2];

    ArrayList<Participant> mParticipants = null;
    String mRoomId = null;
    String mMyId = null;
    String mIncomingInvitationId = null;

    private SoundPool sounds;

    private int scorePoint;
    private int losePoint;
    private int loseGame;
    private int winGame;
    private EventsData events;
    public int highScore = 0;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        events = new EventsData(this);
        try{
           // addEvent(1);

            Cursor cursor = getEvents();
            showEvents(cursor);
        }finally {
            events.close();
        }


        // Skapar Google api objektet
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Games.API).addScope(Games.SCOPE_GAMES)
                .build();

        // Sätter en ClickListener för allt som ska gå att klicka på
        for (int id : CLICKABLES) {
            findViewById(id).setOnClickListener(this);
        }

        sounds =   new SoundPool(10, AudioManager.STREAM_MUSIC, 0);
        scorePoint = sounds.load(this, R.raw.scoreonepoint, 1);
        losePoint = sounds.load(this, R.raw.loseonepoint,1);
        winGame = sounds.load(this, R.raw.victorysound, 1);
        loseGame = sounds.load(this, R.raw.losesound,1);

    }


    /*
     *
     *Kollar om någon klickbarknapp blivigt tryckt
     */
    @Override
    public void onClick(View v) {
        Intent intent;

        switch (v.getId()) {

            case R.id.button_single_player:
                resetGameVars();
                startGame(false);
                break;

            case R.id.button_sign_in:

                //kollar så att det är rätt app_id
                if (!BaseGameUtils.verifySampleSetup(this, R.string.app_id)) {
                    Log.w(TAG, "*** Warning: setup problems detected. Sign in may not work!");
                }
                mSignInClicked = true;
                mGoogleApiClient.connect();
                break;
            case R.id.button_sign_out:

                mSignInClicked = false;
                Games.signOut(mGoogleApiClient);
                mGoogleApiClient.disconnect();
                switchToScreen(R.id.screen_sign_in);
                break;
            case R.id.button_invite_players:

                intent = Games.RealTimeMultiplayer.getSelectOpponentsIntent(mGoogleApiClient, 1, 1);
                switchToScreen(R.id.screen_wait);
                startActivityForResult(intent, RC_SELECT_PLAYERS);
                break;
            case R.id.button_see_invitations:

                // Visar lista på alla inbjudna som inte har accepterat
                intent = Games.Invitations.getInvitationInboxIntent(mGoogleApiClient);
                switchToScreen(R.id.screen_wait);
                startActivityForResult(intent, RC_INVITATION_INBOX);
                break;

            case R.id.button_accept_popup_invitation:
                // Visar en Puppupknapp för en inbjudan
                acceptInviteToRoom(mIncomingInvitationId);
                mIncomingInvitationId = null;
                break;

            case R.id.button_high:
                gamePlay(1);

                break;
            case R.id.button_low:
                gamePlay(0);
                break;
        }
    }

    /**
     * Alla olika states i Google API:t
     * @param requestCode
     * @param responseCode
     * @param intent
     */
    @Override
    public void onActivityResult(int requestCode, int responseCode,
                                 Intent intent) {
        super.onActivityResult(requestCode, responseCode, intent);

        switch (requestCode) {
            case RC_SELECT_PLAYERS:

                handleSelectPlayersResult(responseCode, intent);
                break;
            case RC_INVITATION_INBOX:

                handleInvitationInboxResult(responseCode, intent);
                break;
            case RC_WAITING_ROOM:

                if (responseCode == Activity.RESULT_OK) {

                    startGame(true);
                } else if (responseCode == GamesActivityResultCodes.RESULT_LEFT_ROOM) {

                    leaveRoom();
                } else if (responseCode == Activity.RESULT_CANCELED) {

                    leaveRoom();
                }
                break;
            case RC_SIGN_IN:

                mSignInClicked = false;
                mResolvingConnectionFailure = false;
                if (responseCode == RESULT_OK) {
                    mGoogleApiClient.connect();
                } else {
                    BaseGameUtils.showActivityResultError(this,requestCode,responseCode, R.string.signin_other_error);
                }
                break;
        }
        super.onActivityResult(requestCode, responseCode, intent);
    }

    /**
     * Kollar så att det är rätt antal inbjudna och skapar rummet
     * @param response
     * @param data
     */
    private void handleSelectPlayersResult(int response, Intent data) {
        if (response != Activity.RESULT_OK) {
            switchToMainScreen();
            return;
        }

        final ArrayList<String> invitees = data.getStringArrayListExtra(Games.EXTRA_PLAYER_IDS);
        Log.d(TAG, "Invitee count: " + invitees.size());

        Bundle autoMatchCriteria = null;
        int minAutoMatchPlayers = data.getIntExtra(Multiplayer.EXTRA_MIN_AUTOMATCH_PLAYERS, 0);
        int maxAutoMatchPlayers = data.getIntExtra(Multiplayer.EXTRA_MAX_AUTOMATCH_PLAYERS, 0);
        if (minAutoMatchPlayers > 0 || maxAutoMatchPlayers > 0) {
            autoMatchCriteria = RoomConfig.createAutoMatchCriteria(
                    minAutoMatchPlayers, maxAutoMatchPlayers, 0);
            Log.d(TAG, "Automatch criteria: " + autoMatchCriteria);
        }

        // Skapar rummet
        Log.d(TAG, "Creating room...");
        RoomConfig.Builder rtmConfigBuilder = RoomConfig.builder(this);
        rtmConfigBuilder.addPlayersToInvite(invitees);
        rtmConfigBuilder.setMessageReceivedListener(this);
        rtmConfigBuilder.setRoomStatusUpdateListener(this);
        if (autoMatchCriteria != null) {
            rtmConfigBuilder.setAutoMatchCriteria(autoMatchCriteria);
        }
        switchToScreen(R.id.screen_wait);
        keepScreenOn();
        resetGameVars();
        Games.RealTimeMultiplayer.create(mGoogleApiClient, rtmConfigBuilder.build());

    }
    private void handleInvitationInboxResult(int response, Intent data) {
        if (response != Activity.RESULT_OK) {

            switchToMainScreen();
            return;
        }

        Invitation inv = data.getExtras().getParcelable(Multiplayer.EXTRA_INVITATION);

        acceptInviteToRoom(inv.getInvitationId());
    }

    /**
     * Accepterar inbjudning
     * @param invId
     */
    void acceptInviteToRoom(String invId) {

        RoomConfig.Builder roomConfigBuilder = RoomConfig.builder(this);
        roomConfigBuilder.setInvitationIdToAccept(invId)
                .setMessageReceivedListener(this)
                .setRoomStatusUpdateListener(this);
        switchToScreen(R.id.screen_wait);
        keepScreenOn();
        resetGameVars();
        Games.RealTimeMultiplayer.join(mGoogleApiClient, roomConfigBuilder.build());
    }

    /**
     * Stoppar och lämnar rummet
     */
    @Override
    public void onStop() {


        // om i rum, så lämna rummet
        leaveRoom();

        // Sluta försöka ha skärmen på
        stopKeepingScreenOn();

        if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
            switchToMainScreen();
        } else {
            switchToScreen(R.id.screen_sign_in);
        }
        super.onStop();
    }

    /**
     * Connectar till GoogleApiClient
     */
    @Override
    public void onStart() {
        if (mGoogleApiClient == null) {
            switchToScreen(R.id.screen_sign_in);
        } else if (!mGoogleApiClient.isConnected()) {
            Log.d(TAG,"Connecting client.");
            switchToScreen(R.id.screen_wait);
            mGoogleApiClient.connect();
        } else {
            Log.w(TAG,
                    "GameHelper: client was already connected on onStart()");
        }
        super.onStart();
    }

    /**
     * Hanterar nyckeln om någon vill lämna mitt i ett spel
     */

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_BACK && mCurScreen == R.id.screen_game) {
            leaveRoom();
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    /**
     * Lämnar rummet
     */
    void leaveRoom() {
        Log.d(TAG, "Leaving room.");
        mSecondsLeft = 0;
        stopKeepingScreenOn();
        if (mRoomId != null) {
            Games.RealTimeMultiplayer.leave(mGoogleApiClient, this, mRoomId);
            mRoomId = null;
            switchToScreen(R.id.screen_wait);
        } else {
            switchToMainScreen();
        }
    }

    /**
     * Byter till VWaitingRoomIntent
     * @param room
     */
    void showWaitingRoom(Room room) {

        final int MIN_PLAYERS = Integer.MAX_VALUE;
        Intent i = Games.RealTimeMultiplayer.getWaitingRoomIntent(mGoogleApiClient, room, MIN_PLAYERS);

        startActivityForResult(i, RC_WAITING_ROOM);
    }

    @Override
    public void onInvitationReceived(Invitation invitation) {

        mIncomingInvitationId = invitation.getInvitationId();
        ((TextView) findViewById(R.id.incoming_invitation_text)).setText(
                invitation.getInviter().getDisplayName() + " " +
                        getString(R.string.is_inviting_you));
        switchToScreen(mCurScreen); // Här visar vi injubningsfönstret
    }

    @Override
    public void onInvitationRemoved(String invitationId) {

        if (mIncomingInvitationId.equals(invitationId)&&mIncomingInvitationId!=null) {
            mIncomingInvitationId = null;
            switchToScreen(mCurScreen); // Här gömmer vi inbjudningsfönstret
        }

    }


    /**
     * Inloggningen har lyckats
     * @param connectionHint
     */
    @Override
    public void onConnected(Bundle connectionHint) {
        // Skapar en lyssnare så att vi kan få notiser när vi är inne i spelet.
        Games.Invitations.registerInvitationListener(mGoogleApiClient, this);

        if (connectionHint != null) {
            Log.d(TAG, "onConnected: connection hint provided. Checking for invite.");
            Invitation inv = connectionHint
                    .getParcelable(Multiplayer.EXTRA_INVITATION);
            if (inv != null && inv.getInvitationId() != null) {
                // fångar up och cachar inbjudnings ID:t
                Log.d(TAG,"onConnected: connection hint has a room invite!");
                acceptInviteToRoom(inv.getInvitationId());
                return;
            }
        }
        switchToMainScreen();

    }

    /**
     * Byter till sign in fönstret om inloggningen misslyckas
     * @param i
     */
    @Override
    public void onConnectionSuspended(int i) {
        Log.d(TAG, "onConnectionSuspended() called. Trying to reconnect.");
        mGoogleApiClient.connect();
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        Log.d(TAG, "onConnectionFailed() called, result: " + connectionResult);

        if (mResolvingConnectionFailure) {
            Log.d(TAG, "onConnectionFailed() ignoring connection failure; already resolving.");
            return;
        }

        if (mSignInClicked || mAutoStartSignInFlow) {
            mAutoStartSignInFlow = false;
            mSignInClicked = false;
            mResolvingConnectionFailure = BaseGameUtils.resolveConnectionFailure(this, mGoogleApiClient,
                    connectionResult, RC_SIGN_IN, getString(R.string.signin_other_error));
        }

        switchToScreen(R.id.screen_sign_in);
    }

    @Override
    public void onConnectedToRoom(Room room) {
        Log.d(TAG, "onConnectedToRoom.");

        //Hämtar alla participantsID
        mParticipants = room.getParticipants();
        mMyId = room.getParticipantId(Games.Players.getCurrentPlayerId(mGoogleApiClient));

        if(mRoomId==null)
            mRoomId = room.getRoomId();
    }


    @Override
    public void onLeftRoom(int statusCode, String roomId) {

        Log.d(TAG, "onLeftRoom, code " + statusCode);
        switchToMainScreen();
    }

    @Override
    public void onDisconnectedFromRoom(Room room) {
        mRoomId = null;
        showGameError();
    }

    void showGameError() {
        BaseGameUtils.makeSimpleDialog(this, getString(R.string.game_problem));
        switchToMainScreen();
    }

    /**
     *  Anropas när ett rum har skapats
     */

    @Override
    public void onRoomCreated(int statusCode, Room room) {
        Log.d(TAG, "onRoomCreated(" + statusCode + ", " + room + ")");
        if (statusCode != GamesStatusCodes.STATUS_OK) {
            Log.e(TAG, "*** Error: onRoomCreated, status " + statusCode);
            showGameError();
            return;
        }
        mRoomId = room.getRoomId();
        showWaitingRoom(room);
    }

    /**
     *  Annropas när rummet är helt anslutet
     * @param statusCode
     * @param room
     */
    @Override
    public void onRoomConnected(int statusCode, Room room) {
        Log.d(TAG, "onRoomConnected(" + statusCode + ", " + room + ")");
        if (statusCode != GamesStatusCodes.STATUS_OK) {
            Log.e(TAG, "*** Error: onRoomConnected, status " + statusCode);
            showGameError();
            return;
        }
        updateRoom(room);
    }

    @Override
    public void onJoinedRoom(int statusCode, Room room) {
        Log.d(TAG, "onJoinedRoom(" + statusCode + ", " + room + ")");
        if (statusCode != GamesStatusCodes.STATUS_OK) {
            Log.e(TAG, "*** Error: onRoomConnected, status " + statusCode);
            showGameError();
            return;
        }

        showWaitingRoom(room);
    }
    @Override
    public void onPeerDeclined(Room room, List<String> arg1) {
        updateRoom(room);
    }

    @Override
    public void onPeerInvitedToRoom(Room room, List<String> arg1) {
        updateRoom(room);
    }

    @Override
    public void onP2PDisconnected(String participant) {
    }

    @Override
    public void onP2PConnected(String participant) {
    }

    @Override
    public void onPeerJoined(Room room, List<String> arg1) {
        updateRoom(room);
    }

    @Override
    public void onPeerLeft(Room room, List<String> peersWhoLeft) {
        updateRoom(room);
    }

    @Override
    public void onRoomAutoMatching(Room room) {
        updateRoom(room);
    }

    @Override
    public void onRoomConnecting(Room room) {
        updateRoom(room);
    }

    @Override
    public void onPeersConnected(Room room, List<String> peers) {
        updateRoom(room);
    }

    @Override
    public void onPeersDisconnected(Room room, List<String> peers) {
        updateRoom(room);
    }

    /**
     * Uppdaterar rummet
     * @param room
     */
    void updateRoom(Room room) {
        if (room != null) {
            mParticipants = room.getParticipants();
        }
        if (mParticipants != null) {
            updatePeerScoresDisplay();
        }
    }

    /**
     * SPEL LOGIK
     */

    int mSecondsLeft = -1;
    final static int GAME_DURATION = 60;
    String valueOfStartNumber;

    int mScore = 0;
    int nextNumber;
    int startNumber;




    /**
     *  Sätter alla variabler innan spelet startas
     */
    void resetGameVars() {
        ((TextView) findViewById(R.id.my_score)).setTextColor(Color.GREEN);
        nextNumber = (int) (Math.random() * 100) + 1;
        startNumber = (int) (Math.random() * 100) + 1;
        mSecondsLeft = GAME_DURATION;
        valueOfStartNumber = String.valueOf(startNumber);
        mScore = 0;
        mParticipantScore.clear();
        mFinishedParticipants.clear();
    }

    /**
     * Ställer om numeren
     */
    void resetNumbers(){

        startNumber = nextNumber;
        nextNumber = (int) (Math.random() * 100) + 1;
        valueOfStartNumber = String.valueOf(startNumber);

    }

    /**
     * Spelet, kollar om rätt knapp trycks på
     * @param i
     */
    void gamePlay(int i) {
        if (i == 1) {
            if (nextNumber >= startNumber) {
                sounds.play(scorePoint, 1.0f, 1.0f, 0 , 0,  1.5f);
                scoreOnePoint();
            } else if (nextNumber < startNumber&&mScore!=0) {
                sounds.play(losePoint, 1.0f, 1.0f, 0 , 0,  1.5f);
                loseOnePoint();
            }

        }else if(i == 0){
            if(nextNumber <= startNumber) {
                sounds.play(scorePoint, 1.0f, 1.0f, 0 , 0,  1.5f);
                scoreOnePoint();
            }else if(nextNumber > startNumber && mScore!=0){
                sounds.play(losePoint, 1.0f, 1.0f, 0 , 0,  1.5f);
                loseOnePoint();
            }
        }
        resetNumbers();
        updateScoreDisplay();

    }

    /**
     * Startar spelet
     * @param multiplayer
     */
    void startGame(final boolean multiplayer) {

        mMultiplayer = multiplayer;
        updateScoreDisplay();
        broadcastScore(false);
        switchToScreen(R.id.screen_game);
        findViewById(R.id.button_low).setVisibility(View.VISIBLE);
        findViewById(R.id.button_high).setVisibility(View.VISIBLE);

        // Kör gameTick() varje sekund för att uppdatera spelet
        final Handler h = new Handler();
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mSecondsLeft <= 0)
                   return;

                gameTick();
                h.postDelayed(this, 1000);
            }
        }, 1000);
    }

    /**
     *  Tittar om spelet är slut och uppdaterar nedräkningen
     */
    void gameTick() {

        if (mSecondsLeft > 0)
            --mSecondsLeft;

        // Uppdaterar nedräkningen
        ((TextView) findViewById(R.id.countdown)).setText("0:" +
                (mSecondsLeft < 10 ? "0" : "") + String.valueOf(mSecondsLeft));

        if (mSecondsLeft <= 0) {
            // Spelet slut
            if(mScore > highScore){
                addEvent(mScore);

            }
            showEvents(getEvents());
            Log.d("highscore", String.valueOf(highScore));
            Log.d("mScore", String.valueOf(mScore));


            findViewById(R.id.button_high).setVisibility(View.GONE);
            findViewById(R.id.button_low).setVisibility(View.GONE);
            broadcastScore(true);


        }

    }

    /**
     * Ökar ett poäng
     */
    void scoreOnePoint() {
        mScore++;
        updateScoreDisplay();
        updatePeerScoresDisplay();


        broadcastScore(false);
    }

    /**
     * Minskar ett poäng
     */
    void loseOnePoint(){
        mScore--;
        updateScoreDisplay();
        updatePeerScoresDisplay();

        broadcastScore(false);
    }

    /*
     *DATABASMETODER
     */

    private static String[] FROM = {_ID,TITLE, };
    private static String ORDER_BY = TIME + " DESC";



    private Cursor getEvents(){

        SQLiteDatabase db = events.getReadableDatabase();
        Cursor cursor = db.query(TABLE_NAME, FROM, null, null, null, null, null);
        if(cursor == null){

        }
        startManagingCursor(cursor);


        return cursor;
    }

    private void addEvent(int i){
        SQLiteDatabase db = events.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(TITLE, i);
        db.insertOrThrow(TABLE_NAME, null, values);
        db.update(TABLE_NAME, values, _ID + "=?", new String[]{String.valueOf(1)});
    }
    private void showEvents(Cursor cursor){
        StringBuilder builder = new StringBuilder("Highscore: ");
        cursor.moveToFirst();
        String title = cursor.getString(1);
        highScore = Integer.parseInt(title);
        builder.append(title).append("\n");

        ((TextView) findViewById(R.id.nextNumber)).setText(builder);

    }


    /**
      * Kommunikationsdel
      */

    Map<String, Integer> mParticipantScore = new HashMap<String, Integer>();

    Set<String> mFinishedParticipants = new HashSet<String>();

    /**
     * Uppdaterar resultatet för alla spelare
     * @param rtm
     */
    @Override
    public void onRealTimeMessageReceived(RealTimeMessage rtm) {
        byte[] buf = rtm.getMessageData();
        String sender = rtm.getSenderParticipantId();
        Log.d(TAG, "Message received: " + (char) buf[0] + "/" + (int) buf[1]);

        if (buf[0] == 'F' || buf[0] == 'U') {
            // Uppdaterar resultat
            int existingScore = mParticipantScore.containsKey(sender) ?
                    mParticipantScore.get(sender) : 0;
            int thisScore = (int) buf[1];
            if (thisScore > existingScore || thisScore < existingScore) {

                mParticipantScore.put(sender, thisScore);
            }

            updatePeerScoresDisplay();

            if ((char) buf[0] == 'F') {
                mFinishedParticipants.add(rtm.getSenderParticipantId());
            }
        }
    }

    /**
     * Visar resultatet för alla spelare
     * @param finalScore
     */
    void broadcastScore(boolean finalScore) {
        if (!mMultiplayer)
            return;
        mMsgBuf[0] = (byte) (finalScore ? 'F' : 'U');

        mMsgBuf[1] = (byte) mScore;


        for (Participant p : mParticipants) {
            if (p.getParticipantId().equals(mMyId))
                continue;
            if (p.getStatus() != Participant.STATUS_JOINED)
                continue;
            if (finalScore) {

                Games.RealTimeMultiplayer.sendReliableMessage(mGoogleApiClient, null, mMsgBuf,
                        mRoomId, p.getParticipantId());
            } else {
                Games.RealTimeMultiplayer.sendUnreliableMessage(mGoogleApiClient, mMsgBuf, mRoomId,
                        p.getParticipantId());
            }
        }
    }
    /**
     * Här sköter vi UI:t
     */
    //Array med alla klickbara knappar
    final static int[] CLICKABLES = {
            R.id.button_accept_popup_invitation, R.id.button_invite_players,
             R.id.button_see_invitations, R.id.button_sign_in,
            R.id.button_sign_out, R.id.button_high, R.id.button_low,R.id.button_single_player
    };

    // Array med alla fönster
    final static int[] SCREENS = {
            R.id.screen_game, R.id.screen_main, R.id.screen_sign_in,
            R.id.screen_wait
    };
    int mCurScreen = -1;

    /**
     * Byter till angiven "screen"
     * @param screenId
     */
    void switchToScreen(int screenId) {
        for (int id : SCREENS) {
            findViewById(id).setVisibility(screenId == id ? View.VISIBLE : View.GONE);
        }
        mCurScreen = screenId;


        boolean showInvPopup;
        if (mIncomingInvitationId == null) {

            showInvPopup = false;
        } else if (mMultiplayer) {

            showInvPopup = (mCurScreen == R.id.screen_main);
        } else {
            showInvPopup = (mCurScreen == R.id.screen_main || mCurScreen == R.id.screen_game);
        }
        findViewById(R.id.invitation_popup).setVisibility(showInvPopup ? View.VISIBLE : View.GONE);
    }

    void switchToMainScreen() {
        if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
            switchToScreen(R.id.screen_main);
        }
        else {
            switchToScreen(R.id.screen_sign_in);
        }
    }

    /**
     * uppdaterar alla textfällt
     */
    void updateScoreDisplay() {

        ((TextView) findViewById(R.id.my_score)).setText("Poäng: " + formatScore(mScore));
        ((TextView) findViewById(R.id.rules)).setText(R.string.instructions);
        ((TextView) findViewById(R.id.randomNumber)).setText(valueOfStartNumber);


    }

    // Gör resultatet tresiffrigt
    String formatScore(int i) {
        if (i < 0)
            i = 0;
        String s = String.valueOf(i);
        return s.length() == 1 ? "00" + s : s.length() == 2 ? "0" + s : s;
    }

    /**
     * Uppdaterar rutan med alla spelares resultat och kollar vem som vann
     *
     */
    void updatePeerScoresDisplay() {
        ((TextView) findViewById(R.id.score0)).setText(formatScore(mScore) + " - Me");
        int[] arr = {
                R.id.score1, R.id.score2, R.id.score3
        };
        int i = 0;

        if (mRoomId != null) {
            for (Participant p : mParticipants) {
                String pid = p.getParticipantId();
                if (pid.equals(mMyId))
                    continue;
                if (p.getStatus() != Participant.STATUS_JOINED)
                    continue;
                int score = mParticipantScore.containsKey(pid) ? mParticipantScore.get(pid) : 0;
                ((TextView) findViewById(arr[i])).setText(formatScore(score) + " - " +
                        p.getDisplayName());
                ++i;

                if(mSecondsLeft <= 0){
                    if(mScore > score){
                        ((TextView) findViewById(R.id.my_score)).setText("Vinnare!");
                    }else if(mScore < score){

                        ((TextView) findViewById(R.id.my_score)).setTextColor(Color.RED);
                        ((TextView) findViewById(R.id.my_score)).setText("Förlorare!");
                    }else if(mScore == score){
                        ((TextView) findViewById(R.id.my_score)).setTextColor(Color.YELLOW);
                        ((TextView) findViewById(R.id.my_score)).setText("Oavgjort!");
                    }
                }

            }

        }
        for (; i < arr.length; ++i) {
            ((TextView) findViewById(arr[i])).setText("");
        }
    }


    /**
      *sätter en flagga så att spelet inte går i sovläge medans handskakningen pågår
      */
    void keepScreenOn() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    void stopKeepingScreenOn() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}