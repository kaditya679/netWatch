package ir.drax.netwatch;

import android.annotation.TargetApi;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import ir.drax.netwatch.cb.NetworkChangeReceiver_navigator;
import ir.drax.netwatch.cb.Ping_navigator;

/*
 *  ***** NetworkChangeReceiver ******
 *
 *  Simple Android library to notify Android device network changes and react properly.
 *
 *  This is the main class to handle events.
 *  As a BroadcastReceiver any network connectivity change will be notified to this class on onReceive()
 *   There is two methods to figure out if a connection established or not:
 *      1- BroadcastReceiver listening to 'android.net.conn.CONNECTIVITY_CHANGE' action.
 *      2- Broadcasting ICMP packets throw the network (Ping/echo requests)
 *
 *  Since in some cases Androids default broadcast system is not working reliably and mostly has delays then there have to be second/backup plan for the same purpose.
 *  After a short research i come up with the Echo idea which looked more efficient between some other solutions.
 *  During testing and profiling on different devices i realized that this combination works great.
 * */


public class NetworkChangeReceiver extends BroadcastReceiver {

    private static String TAG = NetworkChangeReceiver.class.getSimpleName();
    private static int DISCONNECTED = 0;
    private static int CONNECTED = 3;
    private static int LAST_STATE = -1;
    private static int NOTIFICATIONS_ID=987231393;//A Random number to identify local notifications
    private static int GENERAL_PING_INTERVAL_MULTIPLIER_MS = 20,GENERAL_PING_INTERVAL_MAX_DELAY = 60000,unchanged_counter = 0;
    private static int notificationIcon = R.drawable.ic_nosignal;
    private static NetworkChangeReceiver_navigator uiNavigator;
    private static String message ;
    private static int repeat = 1 ;
    private static Handler pingHandler = new Handler();
    private static Ping ping = new Ping();
    private static boolean cancelable = true, notificationEnabled = true;
    private static  NotificationCompat.Builder mBuilder;


    public NetworkChangeReceiver() {
        super();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (LAST_STATE == getConnectivityStatus(context))return;

        unchanged_counter = 0;
        checkState(context , 4);
    }

    private static void detectAndAct(Context context, int status){
        if (LAST_STATE == status)return;
        else unchanged_counter=0;//reset counter

        if (status == DISCONNECTED) {
            if (uiNavigator == null){
                hideNotification(context);//do not care about net changes when app is closed
                return;
            }
            else uiNavigator.onDisconnected();

            if (notificationEnabled) {
                if (mBuilder == null) {

                    Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), getNotificationIcon());
                    mBuilder = new NotificationCompat.Builder(context)
                            .setSmallIcon(getNotificationIcon())
                            .setLargeIcon(bitmap)
                            .setColor(Color.parseColor("#ffffff"))
                            .setContentTitle(message == null ? context.getString(R.string.netwatch_lost_connection) : message)
                            .setAutoCancel(true)
                            .setOngoing(!cancelable);
                }
                NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    mBuilder.setChannelId(TAG);
                    createChannel(notificationManager);
                }

                notificationManager.notify(NOTIFICATIONS_ID, mBuilder.build());
            }
        } else {
            hideNotification(context);
            if (uiNavigator!=null)
                uiNavigator.onConnected(getConnectionType(context));
        }

        LAST_STATE = status;
    }

    /**
     * Hide relevant notification card from top sidebar
     * @param context
     * Creates 'NotificationManager' from Context and then tries to hide Notification Card
     */
    public static void hideNotification(Context context){
        try {
            NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.cancel(NOTIFICATIONS_ID);
        }catch (Exception ignored){

        }
    }

    /**
     * @param context
     * @return Connectivity status as a Code
     * Return value can be :DISCONNECTED=0 or :CONNECTED=3
     */
    private static int getConnectivityStatus(Context context) {

        ConnectivityManager cm = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);

        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();

        if (null != activeNetwork) {

            return CONNECTED;
        }
        return DISCONNECTED;
    }

    /**
     * getConnectionType()  Detects the connection type if one existed
     * @param context;
     * @return Type of Connection returned by this method can be: TYPE_WIFI=1 or :TYPE_MOBILE=2
     */
    private static int getConnectionType(Context context) {

        ConnectivityManager cm = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);

        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();

        if (null != activeNetwork) {

            int CONNECTED_WIFI = 1;
            if (activeNetwork.getType() == ConnectivityManager.TYPE_WIFI)
                return CONNECTED_WIFI;

            int CONNECTED_MOBILE = 2;
            if (activeNetwork.getType() == ConnectivityManager.TYPE_MOBILE)
                return CONNECTED_MOBILE;
        }
        return DISCONNECTED;
    }

    private static int getNotificationIcon() {
        return notificationIcon;
    }

    @TargetApi(Build.VERSION_CODES.O)
    private static void createChannel(NotificationManager manager) {
        int importance = NotificationManager.IMPORTANCE_HIGH;

        NotificationChannel mChannel = new NotificationChannel(TAG, TAG, importance);
        mChannel.setDescription("");
        mChannel.enableLights(true);
        mChannel.setLightColor(Color.BLUE);
        manager.createNotificationChannel(mChannel);
    }

    public static void setNotificationIcon(int notificationIcon) {
        NetworkChangeReceiver.notificationIcon = notificationIcon;
    }

    public static void setUiNavigator(NetworkChangeReceiver_navigator uiNavigator) {
        NetworkChangeReceiver.uiNavigator = uiNavigator;
    }

    public static void setMessage(String message) {
        NetworkChangeReceiver.message = message;
    }

    public static void checkState(Context context){
        checkState(context,repeat);
    }
    /**
     * checkState() Tries to detects and understand connectivity in 2 available ways.
     *
     * @param context
     * @param repeat
     *
     * restarts ping interval to make detection more sensitive by shorter ping delays
     */
    public static  void checkState(Context context,int repeat){
        if (repeat==0){
            NetworkChangeReceiver.repeat = 1;

        }else {
            NetworkChangeReceiver.repeat = repeat;
        }
        pingHandler.removeCallbacks(ping);
        pingHandler.postDelayed(ping.setContext(context).setCb(new Ping_navigator() {
            @Override
            public void timeout(Context context) {
                if (NetworkChangeReceiver.repeat == 1) {
                    detectAndAct(context, NetworkChangeReceiver.DISCONNECTED);
                }
            }

            @Override
            public void replied(Context context) {

                detectAndAct(context ,NetworkChangeReceiver.CONNECTED);
                NetworkChangeReceiver.repeat = 1 ;
            }

            @Override
            public void ended(Context context) {
                unchanged_counter ++;
                NetworkChangeReceiver.repeat = NetworkChangeReceiver.repeat - 1;
                checkState(context ,NetworkChangeReceiver.repeat );
            }
        }),getDelay());
    }

    private static long getDelay() {
        long delay;

        delay = unchanged_counter * unchanged_counter * GENERAL_PING_INTERVAL_MULTIPLIER_MS;
        if (delay > GENERAL_PING_INTERVAL_MAX_DELAY)
            delay = GENERAL_PING_INTERVAL_MAX_DELAY;


        Log.e(TAG , unchanged_counter+"=="+delay);
        return delay;
    }

    public static void setCancelable(boolean cancelable) {
        NetworkChangeReceiver.cancelable = cancelable;
    }

    public static void setNotificationEnabled(boolean notificationEnabled) {
        NetworkChangeReceiver.notificationEnabled = notificationEnabled;
    }

    public static void setNotificationBuilder(NotificationCompat.Builder mBuilder) {
        NetworkChangeReceiver.mBuilder = mBuilder;
    }

    /**
     * unregister() Removes listeners , receivers and notification on app closure
     * @param context
     */
    public void unregister(Context context){
        hideNotification(context);
        try {
            context.unregisterReceiver(this);
        } catch(IllegalArgumentException ignored) {

        }
    }
}