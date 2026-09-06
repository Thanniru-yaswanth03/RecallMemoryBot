package com.recallbot.admin.activity;

import com.recallbot.core.message.event.MessagePersistedEvent;
import com.recallbot.telegram.event.TelegramUpdateReceivedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Listens to application events and logs operational metadata into the AdminActivityBuffer.
 */
@Component
public class AdminActivityEventListener {

    private final AdminActivityBuffer activityBuffer;

    public AdminActivityEventListener(AdminActivityBuffer activityBuffer) {
        this.activityBuffer = activityBuffer;
    }

    @EventListener
    public void onTelegramUpdate(TelegramUpdateReceivedEvent event) {
        if (event == null || event.update() == null) {
            return;
        }

        Long updateId = event.update().updateId();
        activityBuffer.recordEvent(new BotActivityEvent(
                "upd-" + updateId,
                Instant.now(),
                "UPDATE_RECEIVED",
                null,
                null,
                "PROCESSED",
                0,
                "Received update_id=" + updateId
        ));
    }

    @EventListener
    public void onMessagePersisted(MessagePersistedEvent event) {
        if (event == null) {
            return;
        }

        activityBuffer.recordEvent(new BotActivityEvent(
                "msg-" + event.messageId(),
                Instant.now(),
                "MESSAGE_INGESTED",
                event.groupId(),
                null,
                "SUCCESS",
                0,
                "Persisted message_id=" + event.messageId() + (event.isEdit() ? " (edit)" : "")
        ));
    }
}
