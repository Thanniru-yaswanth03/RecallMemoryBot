package com.recallbot.telegram.command;

import com.recallbot.telegram.dto.ChatDto;
import com.recallbot.telegram.dto.MessageDto;
import com.recallbot.telegram.dto.UserDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommandDispatcherTest {

    @Mock
    private CommandHandler askHandler;

    private CommandDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        lenient().when(askHandler.canHandle("/ask")).thenReturn(true);
        dispatcher = new CommandDispatcher(List.of(askHandler));
    }

    @Test
    @DisplayName("Dispatches /ask command to AskCommandHandler")
    void dispatchesAskCommand() {
        MessageDto message = createMessage("/ask when is the meeting?");
        dispatcher.dispatch(message);

        verify(askHandler).handle(message);
    }

    @Test
    @DisplayName("Strips bot username suffix and dispatches /ask@RecallMemoryBot to AskCommandHandler")
    void dispatchesAskCommandWithBotUsername() {
        MessageDto message = createMessage("/ask@RecallMemoryBot when is the meeting?");
        dispatcher.dispatch(message);

        verify(askHandler).handle(message);
    }

    @Test
    @DisplayName("Ignores non-command message")
    void ignoresNonCommand() {
        MessageDto message = createMessage("Just a regular conversation message");
        dispatcher.dispatch(message);

        verify(askHandler, never()).handle(any());
    }

    @Test
    @DisplayName("Ignores unrecognized command safely")
    void ignoresUnrecognizedCommand() {
        MessageDto message = createMessage("/unknownCommand do something");
        dispatcher.dispatch(message);

        verify(askHandler, never()).handle(any());
    }

    private MessageDto createMessage(String text) {
        ChatDto chat = new ChatDto(-100123L, "supergroup", "Test Group", null);
        UserDto user = new UserDto(456L, false, "alice", "Alice", null);
        return new MessageDto(1001L, user, chat, 1700000000L, text, null, null);
    }
}
