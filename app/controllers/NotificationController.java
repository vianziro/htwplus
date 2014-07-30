package controllers;

import java.util.List;
import java.util.concurrent.TimeUnit;

import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.Props;
import com.fasterxml.jackson.databind.JsonNode;
import models.Account;
import models.NewNotification;
import models.Notification;
import models.actors.NewNotificationActor;
import play.Logger;
import play.Play;
import play.api.templates.Html;
import play.db.jpa.Transactional;
import play.libs.Akka;
import play.libs.F;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.Security;
import play.mvc.WebSocket;
import scala.collection.mutable.StringBuilder;
import scala.concurrent.duration.Duration;
import views.html.Notification.view;

@Transactional
@Security.Authenticated(Secured.class)
public class NotificationController extends BaseController {
    static final int LIMIT = Integer.parseInt(Play.application().configuration().getString("htwplus.notification.limit"));

    /**
     * @deprecated Deprecated since refactor of notification system
     * @return Html
     */
	public static Html view() {
		return getNotifications();
	}

    /**
     * @deprecated Deprecated since refactor of notification system
     * @return Result
     */
	public static Result viewAjax() {
		return ok(NotificationController.getNotifications());
	}

    /**
     * @deprecated Deprecated since refactor of notification system
     * @return Result
     */
    public static Result viewHistoryAjax() {
        return ok(NotificationController.getNotifications());
    }

    /**
     * Returns all notifications for current user.
     *
     * @return Html rendered instance
     */
    @Transactional(readOnly = true)
	private static Html getNotifications() {
		Account account = Component.currentAccount();
		
		if (account == null) {
			return new Html(new StringBuilder("Das wird nichts"));
		}

        List<NewNotification> list = null;
        try {
            list = NewNotification.findByAccountId(account.id);
        } catch (Throwable throwable) { throwable.printStackTrace(); }

        return views.html.Notification.menuitem.render(list);
	}

    /**
     * Redirects to the target URL of the notification, if user has access.
     *
     * @param notificationId ID of the notification
     * @return SimpleResult with redirection
     */
    @Transactional
	public static Result forward(Long notificationId) {
		NewNotification notification = NewNotification.findById(notificationId);

		if (notification == null) {
			return badRequest("Das gibts doch garnicht!");
		}
		
		if (!Secured.hasAccessToNotification(notification)) {
			return redirect(controllers.routes.Application.index());
		}

        notification.isRead = true;
        notification.update();

		return redirect(notification.targetUrl);
	}

    /**
     * Shows all notifications.
     *
     * @param page Current page
     * @return Result
     */
    @Transactional(readOnly = true)
    public static Result showAll(int page) {
        List<NewNotification> notifications = null;
        try {
            notifications = NewNotification.findByAccountIdForPage(Component.currentAccount().id, NotificationController.LIMIT, page);
        } catch (Throwable throwable) { throwable.printStackTrace(); }

        return ok(view.render(notifications, LIMIT, page));
    }

    /**
     * @deprecated Deprecated since refactor of notification system
     * @return Result
     */
	public static Result deleteAll() {
        Notification.markAllAsReadByUser(Component.currentAccount());
        flash("success", "Alle Neuigkeiten wurden als gelesen markiert.");
		return ok();
	}

    /**
     * Returns the current account ID from current HTTP context.
     *
     * @return Account ID of current user
     */
    private static Long getCurrentAccountId() {
        try {
            return Long.valueOf(Http.Context.current().session().get("id"));
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Handles the web socket channel by invoking Akka actor that writes notifications to the web socket.
     *
     * @return Web socket instance including JSON nodes
     */
    @Transactional(readOnly = true)
    public static WebSocket<JsonNode> wsNotifications() {
        final Long accountId = NotificationController.getCurrentAccountId();

        // called when the Websocket Handshake is done.
        return new WebSocket<JsonNode>() {
            @Override
            public void onReady(In<JsonNode> in, Out<JsonNode> out) {
                if (accountId == null) {
                    out.close();
                }

                final ActorRef newNotificationActor = Akka.system().actorOf(Props.create(NewNotificationActor.class, in, out, accountId));
                final Cancellable cancellable = Akka.system().scheduler().schedule(
                        Duration.create(20, TimeUnit.SECONDS),
                        Duration.create(20, TimeUnit.SECONDS),
                        newNotificationActor,
                        "getNotifications",
                        Akka.system().dispatcher(),
                        null
                );

                // For each event received on the socket,
                in.onMessage(new F.Callback<JsonNode>() {
                    public void invoke(JsonNode wsMessage) {
                        // Log events to the console
                        Logger.info(wsMessage.asText());
                    }
                });

                // When the socket is closed.
                in.onClose(new F.Callback0() {
                    public void invoke() {
                        cancellable.cancel();
                        Logger.info("Disconnected user ID: " + accountId);
                    }
                });
            }
        };
    }
}
