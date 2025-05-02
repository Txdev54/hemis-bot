package org.example;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.Contact;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

public class TelegramBot extends TelegramLongPollingBot {
    private static final String BOT_TOKEN = "7600544866:AAEmDkKTJPyenpjASMDxqll8_qGBVtYKT5k";
    private static final String BOT_USERNAME = "@hemis51_bot";

    // Настройки для отправки email через Gmail SMTP
    private static final String SMTP_HOST = "smtp.gmail.com";
    private static final String SMTP_PORT = "587";
    private static final String SMTP_USERNAME = "your-email@gmail.com"; // Замените на ваш email
    private static final String SMTP_PASSWORD = "your-app-password"; // Замените на пароль приложения Gmail

    // Состояния для регистрации и создания учетных данных HEMIS
    private static final int STATE_NONE = 0;
    private static final int STATE_WAITING_NAME = 1;
    private static final int STATE_WAITING_CONTACT = 2;
    private static final int STATE_WAITING_EMAIL = 3;
    private static final int STATE_WAITING_HEMIS_LOGIN = 4;
    private static final int STATE_WAITING_HEMIS_PASSWORD = 5;
    private static final int STATE_WAITING_LOGIN_CREDENTIALS = 6;
    private static final int STATE_WAITING_PASSWORD_CREDENTIALS = 7;
    private static final int STATE_WAITING_BROADCAST_MESSAGE = 8;
    private static final int STATE_WAITING_USER_ID = 9;
    private static final int STATE_WAITING_PRIVATE_MESSAGE = 10;
    private static final int STATE_WAITING_EMAIL_CODE = 11; // Новое состояние для ожидания кода

    // Хранилище состояний пользователей
    private final Map<Long, Integer> userStates = new HashMap<>();
    private final Map<Long, UserData> userData = new HashMap<>();
    private final Map<Long, String> verificationCodes = new HashMap<>(); // Хранилище проверочных кодов

    // Временное хранилище данных пользователя
    private static class UserData {
        String name;
        String phoneNumber;
        String email;
        boolean isAdmin;
        String hemisLogin;
        String targetUserId;
    }

    public TelegramBot() {
        initDatabase();
    }

    @Override
    public String getBotUsername() {
        return BOT_USERNAME;
    }

    @Override
    public String getBotToken() {
        return BOT_TOKEN;
    }

    private void initDatabase() {
        try {
            Class.forName("org.sqlite.JDBC");
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:users.db")) {
                Statement stmt = conn.createStatement();

                // Таблица пользователей с новым столбцом email
                String sqlUsers = "CREATE TABLE IF NOT EXISTS users (" +
                        "chat_id INTEGER PRIMARY KEY," +
                        "user_id TEXT UNIQUE," +
                        "is_admin INTEGER," +
                        "first_name TEXT," +
                        "last_name TEXT," +
                        "phone_number TEXT," +
                        "email TEXT," +
                        "registration_time TEXT)";
                stmt.execute(sqlUsers);

                // Проверяем, существуют ли столбцы user_id, email и registration_time
                ResultSet rs = stmt.executeQuery("PRAGMA table_info(users)");
                boolean hasUserIdColumn = false;
                boolean hasEmailColumn = false;
                boolean hasRegistrationTimeColumn = false;
                while (rs.next()) {
                    String columnName = rs.getString("name");
                    if ("user_id".equals(columnName)) {
                        hasUserIdColumn = true;
                    }
                    if ("email".equals(columnName)) {
                        hasEmailColumn = true;
                    }
                    if ("registration_time".equals(columnName)) {
                        hasRegistrationTimeColumn = true;
                    }
                }

                // Если столбца user_id нет, добавляем его
                if (!hasUserIdColumn) {
                    stmt.execute("ALTER TABLE users ADD COLUMN user_id TEXT");
                    ResultSet users = stmt.executeQuery("SELECT chat_id FROM users WHERE user_id IS NULL");
                    Random random = new Random();
                    while (users.next()) {
                        long chatId = users.getLong("chat_id");
                        String userId;
                        boolean unique;
                        do {
                            userId = String.format("%04d", random.nextInt(10000));
                            PreparedStatement checkStmt = conn.prepareStatement("SELECT COUNT(*) FROM users WHERE user_id = ?");
                            checkStmt.setString(1, userId);
                            ResultSet checkRs = checkStmt.executeQuery();
                            unique = checkRs.next() && checkRs.getInt(1) == 0;
                        } while (!unique);
                        PreparedStatement updateStmt = conn.prepareStatement("UPDATE users SET user_id = ? WHERE chat_id = ?");
                        updateStmt.setString(1, userId);
                        updateStmt.setLong(2, chatId);
                        updateStmt.executeUpdate();
                    }
                }

                // Если столбца email нет, добавляем его
                if (!hasEmailColumn) {
                    stmt.execute("ALTER TABLE users ADD COLUMN email TEXT");
                }

                // Если столбца registration_time нет, добавляем его
                if (!hasRegistrationTimeColumn) {
                    stmt.execute("ALTER TABLE users ADD COLUMN registration_time TEXT");
                }

                // Таблица учетных данных HEMIS (убираем telegram_id)
                String sqlCredentials = "CREATE TABLE IF NOT EXISTS hemis_credentials (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "chat_id INTEGER," +
                        "hemis_login TEXT UNIQUE," +
                        "hemis_password TEXT," +
                        "FOREIGN KEY(chat_id) REFERENCES users(chat_id))";
                stmt.execute(sqlCredentials);

                // Новая таблица для связи учетных записей HEMIS и Telegram-аккаунтов
                String sqlHemisTelegram = "CREATE TABLE IF NOT EXISTS hemis_credentials_telegram (" +
                        "hemis_id INTEGER," +
                        "telegram_id INTEGER," +
                        "PRIMARY KEY (hemis_id, telegram_id)," +
                        "FOREIGN KEY(hemis_id) REFERENCES hemis_credentials(id))";
                stmt.execute(sqlHemisTelegram);

                // Если старая таблица hemis_credentials содержит telegram_id, переносим данные
                rs = stmt.executeQuery("PRAGMA table_info(hemis_credentials)");
                boolean hasTelegramIdColumn = false;
                while (rs.next()) {
                    if ("telegram_id".equals(rs.getString("name"))) {
                        hasTelegramIdColumn = true;
                        break;
                    }
                }
                if (hasTelegramIdColumn) {
                    // Копируем данные из telegram_id в новую таблицу
                    ResultSet credentialsRs = stmt.executeQuery("SELECT id, telegram_id FROM hemis_credentials WHERE telegram_id IS NOT NULL");
                    while (credentialsRs.next()) {
                        long hemisId = credentialsRs.getLong("id");
                        long telegramId = credentialsRs.getLong("telegram_id");
                        PreparedStatement insertStmt = conn.prepareStatement(
                                "INSERT OR IGNORE INTO hemis_credentials_telegram (hemis_id, telegram_id) VALUES (?, ?)");
                        insertStmt.setLong(1, hemisId);
                        insertStmt.setLong(2, telegramId);
                        insertStmt.executeUpdate();
                    }
                    // Удаляем столбец telegram_id из hemis_credentials
                    stmt.execute("CREATE TABLE hemis_credentials_temp AS SELECT id, chat_id, hemis_login, hemis_password FROM hemis_credentials");
                    stmt.execute("DROP TABLE hemis_credentials");
                    stmt.execute("ALTER TABLE hemis_credentials_temp RENAME TO hemis_credentials");
                    stmt.execute("CREATE UNIQUE INDEX idx_hemis_login ON hemis_credentials(hemis_login)");
                }
            }
        } catch (SQLException | ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    private boolean isAdmin(long chatId) {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:users.db")) {
            PreparedStatement pstmt = conn.prepareStatement("SELECT is_admin FROM users WHERE chat_id = ?");
            pstmt.setLong(1, chatId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("is_admin") == 1;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean isUserRegistered(long chatId) {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:users.db")) {
            PreparedStatement pstmt = conn.prepareStatement("SELECT COUNT(*) FROM users WHERE chat_id = ?");
            pstmt.setLong(1, chatId);
            ResultSet rs = pstmt.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean hasEmail(long chatId) {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:users.db")) {
            PreparedStatement pstmt = conn.prepareStatement("SELECT email FROM users WHERE chat_id = ?");
            pstmt.setLong(1, chatId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                String email = rs.getString("email");
                return email != null && !email.trim().isEmpty();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean hasAdmin() {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:users.db")) {
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM users WHERE is_admin = 1");
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean hasHemisCredentials(long telegramId) {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:users.db")) {
            PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT COUNT(*) FROM hemis_credentials_telegram WHERE telegram_id = ?");
            pstmt.setLong(1, telegramId);
            ResultSet rs = pstmt.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean checkHemisLoginExists(String login) {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:users.db")) {
            PreparedStatement pstmt = conn.prepareStatement("SELECT COUNT(*) FROM hemis_credentials WHERE hemis_login = ?");
            pstmt.setString(1, login);
            ResultSet rs = pstmt.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean verifyHemisCredentials(String login, String password) {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:users.db")) {
            PreparedStatement pstmt = conn.prepareStatement("SELECT COUNT(*) FROM hemis_credentials WHERE hemis_login = ? AND hemis_password = ?");
            pstmt.setString(1, login);
            pstmt.setString(2, password);
            ResultSet rs = pstmt.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    private long getHemisIdByLogin(String login) {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:users.db")) {
            PreparedStatement pstmt = conn.prepareStatement("SELECT id FROM hemis_credentials WHERE hemis_login = ?");
            pstmt.setString(1, login);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getLong("id");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return -1;
    }

    private void linkHemisAccount(long chatId, String login) throws SQLException {
        long hemisId = getHemisIdByLogin(login);
        if (hemisId == -1) {
            throw new SQLException("Учетная запись HEMIS с таким логином не найдена.");
        }

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:users.db")) {
            // Добавляем связь между учетной записью HEMIS и Telegram-аккаунтом
            PreparedStatement pstmt = conn.prepareStatement(
                    "INSERT OR IGNORE INTO hemis_credentials_telegram (hemis_id, telegram_id) VALUES (?, ?)");
            pstmt.setLong(1, hemisId);
            pstmt.setLong(2, chatId);
            pstmt.executeUpdate();
        }
    }

    private void deleteEmail(long chatId) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:users.db")) {
            PreparedStatement pstmt = conn.prepareStatement("UPDATE users SET email = NULL WHERE chat_id = ?");
            pstmt.setLong(1, chatId);
            pstmt.executeUpdate();
        }
    }

    private String generateVerificationCode() {
        Random random = new Random();
        return String.format("%06d", random.nextInt(1000000)); // Генерируем 6-значный код
    }

    private void sendVerificationEmail(String email, String code) throws MessagingException {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", SMTP_HOST);
        props.put("mail.smtp.port", SMTP_PORT);

        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(SMTP_USERNAME, SMTP_PASSWORD);
            }
        });

        Message message = new MimeMessage(session);
        message.setFrom(new InternetAddress(SMTP_USERNAME));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(email));
        message.setSubject("Проверочный код для Telegram-бота");
        message.setText("Ваш проверочный код: " + code);

        Transport.send(message);
    }

    private String getUserDetails(String login) {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:users.db")) {
            PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT u.first_name, u.last_name, u.phone_number, u.email " +
                            "FROM users u " +
                            "JOIN hemis_credentials hc ON u.chat_id = hc.chat_id " +
                            "WHERE hc.hemis_login = ?");
            pstmt.setString(1, login);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                String firstName = rs.getString("first_name");
                String lastName = rs.getString("last_name");
                String phoneNumber = rs.getString("phone_number");
                String email = rs.getString("email");
                return String.format("Данные учетной записи:\nИмя: %s\nФамилия: %s\nТелефон: %s\nEmail: %s",
                        firstName, lastName, phoneNumber, email != null ? email : "Не указано");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return "Не удалось найти данные учетной записи.";
    }

    private void broadcastMessage(String messageText) {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:users.db")) {
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT chat_id FROM users");

            while (rs.next()) {
                long chatId = rs.getLong("chat_id");
                SendMessage message = new SendMessage();
                message.setChatId(chatId);
                message.setText("Сообщение от администратора:\n" + messageText);
                try {
                    execute(message);
                } catch (TelegramApiException e) {
                    System.err.println("Не удалось отправить сообщение пользователю " + chatId + ": " + e.getMessage());
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private long getChatIdByUserId(String userId) {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:users.db")) {
            PreparedStatement pstmt = conn.prepareStatement("SELECT chat_id FROM users WHERE user_id = ?");
            pstmt.setString(1, userId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getLong("chat_id");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return -1;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage()) return;

        long chatId = update.getMessage().getChatId();
        String messageText = update.getMessage().hasText() ? update.getMessage().getText() : "";
        Contact contact = update.getMessage().hasContact() ? update.getMessage().getContact() : null;

        try {
            if (messageText.equals("/start")) {
                handleStartCommand(chatId);
                return;
            }

            Integer state = userStates.getOrDefault(chatId, STATE_NONE);
            if (state == STATE_WAITING_NAME) {
                handleNameInput(chatId, messageText);
            } else if (state == STATE_WAITING_CONTACT && contact != null) {
                handleContactInput(chatId, contact);
            } else if (state == STATE_WAITING_EMAIL) {
                handleEmailInput(chatId, messageText);
            } else if (state == STATE_WAITING_EMAIL_CODE) {
                handleEmailCodeInput(chatId, messageText);
            } else if (state == STATE_WAITING_HEMIS_LOGIN) {
                handleHemisLoginInput(chatId, messageText);
            } else if (state == STATE_WAITING_HEMIS_PASSWORD) {
                handleHemisPasswordInput(chatId, messageText);
            } else if (state == STATE_WAITING_LOGIN_CREDENTIALS) {
                handleLoginCredentialsInput(chatId, messageText);
            } else if (state == STATE_WAITING_PASSWORD_CREDENTIALS) {
                handlePasswordCredentialsInput(chatId, messageText);
            } else if (state == STATE_WAITING_BROADCAST_MESSAGE) {
                handleBroadcastMessageInput(chatId, messageText);
            } else if (state == STATE_WAITING_USER_ID) {
                handleUserIdInput(chatId, messageText);
            } else if (state == STATE_WAITING_PRIVATE_MESSAGE) {
                handlePrivateMessageInput(chatId, messageText);
            } else {
                handleMenuSelection(chatId, messageText);
            }
        } catch (TelegramApiException e) {
            e.printStackTrace();
            try {
                SendMessage errorMessage = new SendMessage();
                errorMessage.setChatId(chatId);
                errorMessage.setText("Произошла ошибка при обработке запроса. Попробуйте снова.");
                execute(errorMessage);
            } catch (TelegramApiException ex) {
                ex.printStackTrace();
            }
        }
    }

    private void handleStartCommand(long chatId) throws TelegramApiException {
        SendMessage message = new SendMessage();
        message.setChatId(chatId);

        // Проверяем, зарегистрирован ли пользователь
        if (!isUserRegistered(chatId)) {
            message.setText("Добро пожаловать! Для начала нужно зарегистрироваться.");
            // Создаем новые данные пользователя
            UserData data = new UserData();
            data.isAdmin = false;
            userData.put(chatId, data);
            userStates.put(chatId, STATE_WAITING_NAME);

            System.out.println("Создан новый UserData для chatId " + chatId);

            ReplyKeyboardRemove removeKeyboard = new ReplyKeyboardRemove();
            removeKeyboard.setRemoveKeyboard(true);
            message.setReplyMarkup(removeKeyboard);
            execute(message);

            SendMessage nameRequest = new SendMessage();
            nameRequest.setChatId(chatId);
            nameRequest.setText("Пожалуйста, напишите ваше имя и фамилию");
            execute(nameRequest);
        } else if (!hasEmail(chatId)) {
            // Если пользователь зарегистрирован, но email отсутствует
            UserData data = userData.get(chatId);
            if (data == null) {
                // Если данные потеряны, создаем их заново
                data = new UserData();
                // Заполняем существующие данные из базы
                try (Connection conn = DriverManager.getConnection("jdbc:sqlite:users.db")) {
                    PreparedStatement pstmt = conn.prepareStatement("SELECT first_name, last_name, phone_number FROM users WHERE chat_id = ?");
                    pstmt.setLong(1, chatId);
                    ResultSet rs = pstmt.executeQuery();
                    if (rs.next()) {
                        data.name = rs.getString("first_name") + " " + rs.getString("last_name");
                        data.phoneNumber = rs.getString("phone_number");
                        data.isAdmin = isAdmin(chatId);
                    }
                } catch (SQLException e) {
                    e.printStackTrace();
                }
                userData.put(chatId, data);
                System.out.println("Восстановлены данные для chatId " + chatId + ": имя = " + data.name + ", телефон = " + data.phoneNumber);
            }
            userStates.put(chatId, STATE_WAITING_EMAIL);

            SendMessage emailRequest = new SendMessage();
            emailRequest.setChatId(chatId);
            emailRequest.setText("Пожалуйста, укажите ваш адрес электронной почты:");
            ReplyKeyboardRemove removeKeyboard = new ReplyKeyboardRemove();
            removeKeyboard.setRemoveKeyboard(true);
            emailRequest.setReplyMarkup(removeKeyboard);
            execute(emailRequest);
        } else {
            message.setText("Добро пожаловать! Выберите меню:");
            message.setReplyMarkup(getMenuKeyboard(chatId));
            execute(message);
        }
    }

    private void handleNameInput(long chatId, String name) throws TelegramApiException {
        UserData data = userData.get(chatId);
        if (data == null) {
            data = new UserData();
            userData.put(chatId, data);
        }
        data.name = name;
        userStates.put(chatId, STATE_WAITING_CONTACT);

        System.out.println("Сохранено имя для chatId " + chatId + ": " + name);

        KeyboardButton contactButton = new KeyboardButton("Поделиться контактом");
        contactButton.setRequestContact(true);
        KeyboardRow row = new KeyboardRow();
        row.add(contactButton);
        List<KeyboardRow> keyboard = new ArrayList<>();
        keyboard.add(row);

        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup();
        markup.setKeyboard(keyboard);
        markup.setResizeKeyboard(true);

        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText("Пожалуйста, поделитесь вашим контактом");
        message.setReplyMarkup(markup);
        execute(message);
    }

    private void handleContactInput(long chatId, Contact contact) throws TelegramApiException {
        UserData data = userData.get(chatId);
        if (data == null) {
            data = new UserData();
            userData.put(chatId, data);
        }
        data.phoneNumber = contact.getPhoneNumber();
        userStates.put(chatId, STATE_WAITING_EMAIL);

        System.out.println("Сохранен номер телефона для chatId " + chatId + ": " + data.phoneNumber);

        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText("Пожалуйста, укажите ваш адрес электронной почты:");
        ReplyKeyboardRemove removeKeyboard = new ReplyKeyboardRemove();
        removeKeyboard.setRemoveKeyboard(true);
        message.setReplyMarkup(removeKeyboard);
        execute(message);
    }

    private void handleEmailInput(long chatId, String email) throws TelegramApiException {
        UserData data = userData.get(chatId);
        if (data == null) {
            System.out.println("Ошибка: данные пользователя не найдены для chatId " + chatId + ". Восстанавливаем...");
            data = new UserData();
            // Пытаемся восстановить данные из базы
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:users.db")) {
                PreparedStatement pstmt = conn.prepareStatement("SELECT first_name, last_name, phone_number FROM users WHERE chat_id = ?");
                pstmt.setLong(1, chatId);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) {
                    data.name = rs.getString("first_name") + " " + rs.getString("last_name");
                    data.phoneNumber = rs.getString("phone_number");
                    data.isAdmin = isAdmin(chatId);
                    userData.put(chatId, data);
                    System.out.println("Данные восстановлены из базы для chatId " + chatId + ": имя = " + data.name + ", телефон = " + data.phoneNumber);
                } else {
                    // Если данных нет в базе, начинаем регистрацию заново
                    SendMessage message = new SendMessage();
                    message.setChatId(chatId);
                    message.setText("Ошибка: данные пользователя не найдены в базе. Пожалуйста, начните регистрацию заново с команды /start.");
                    execute(message);
                    userStates.remove(chatId);
                    return;
                }
            } catch (SQLException e) {
                e.printStackTrace();
                SendMessage message = new SendMessage();
                message.setChatId(chatId);
                message.setText("Ошибка при восстановлении данных: " + e.getMessage() + ". Пожалуйста, начните регистрацию заново с команды /start.");
                execute(message);
                userStates.remove(chatId);
                return;
            }
        }

        // Простая проверка формата email
        if (!email.matches("^[A-Za-z0-9+_.-]+@(.+)$")) {
            SendMessage message = new SendMessage();
            message.setChatId(chatId);
            message.setText("Неверный формат электронной почты. Пожалуйста, попробуйте снова:");
            execute(message);
            return;
        }

        data.email = email;
        System.out.println("Сохранен email для chatId " + chatId + ": " + email);

        // Генерируем и отправляем проверочный код
        String code = generateVerificationCode();
        verificationCodes.put(chatId, code);
        try {
            sendVerificationEmail(email, code);
            userStates.put(chatId, STATE_WAITING_EMAIL_CODE);

            SendMessage message = new SendMessage();
            message.setChatId(chatId);
            message.setText("Проверочный код отправлен на ваш email. Пожалуйста, введите код:");
            execute(message);
        } catch (MessagingException e) {
            e.printStackTrace();
            SendMessage message = new SendMessage();
            message.setChatId(chatId);
            message.setText("Ошибка при отправке проверочного кода: " + e.getMessage() + ". Попробуйте снова:");
            execute(message);
            userStates.put(chatId, STATE_WAITING_EMAIL); // Возвращаем в состояние ожидания email
            verificationCodes.remove(chatId);
        }
    }

    private void handleEmailCodeInput(long chatId, String code) throws TelegramApiException {
        UserData data = userData.get(chatId);
        if (data == null) {
            SendMessage message = new SendMessage();
            message.setChatId(chatId);
            message.setText("Ошибка: данные пользователя потеряны. Пожалуйста, начните регистрацию заново с команды /start.");
            execute(message);
            userStates.remove(chatId);
            verificationCodes.remove(chatId);
            return;
        }

        String expectedCode = verificationCodes.get(chatId);
        if (expectedCode == null || !expectedCode.equals(code)) {
            SendMessage message = new SendMessage();
            message.setChatId(chatId);
            message.setText("Неверный код. Пожалуйста, попробуйте снова:");
            execute(message);
            return;
        }

        // Код верный, сохраняем email
        try {
            // Если это новый пользователь (есть данные в userData), сохраняем полную регистрацию
            if (data.name != null && data.phoneNumber != null && !isUserRegistered(chatId)) {
                String[] nameParts = data.name.split("\\s+", 2);
                String firstName = nameParts[0];
                String lastName = nameParts.length > 1 ? nameParts[1] : "";
                LocalDateTime now = LocalDateTime.now();
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                String registrationTime = now.format(formatter);

                String userId;
                Random random = new Random();
                try (Connection conn = DriverManager.getConnection("jdbc:sqlite:users.db")) {
                    boolean unique;
                    do {
                        userId = String.format("%04d", random.nextInt(10000));
                        PreparedStatement checkStmt = conn.prepareStatement("SELECT COUNT(*) FROM users WHERE user_id = ?");
                        checkStmt.setString(1, userId);
                        ResultSet rs = checkStmt.executeQuery();
                        unique = rs.next() && rs.getInt(1) == 0;
                    } while (!unique);

                    PreparedStatement pstmt = conn.prepareStatement(
                            "INSERT INTO users (chat_id, user_id, is_admin, first_name, last_name, phone_number, email, registration_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
                    pstmt.setLong(1, chatId);
                    pstmt.setString(2, userId);
                    pstmt.setInt(3, data.isAdmin ? 1 : 0);
                    pstmt.setString(4, firstName);
                    pstmt.setString(5, lastName);
                    pstmt.setString(6, data.phoneNumber);
                    pstmt.setString(7, data.email);
                    pstmt.setString(8, registrationTime);
                    pstmt.executeUpdate();

                    System.out.println("Пользователь успешно сохранен в базе данных для chatId " + chatId);
                } catch (SQLException e) {
                    e.printStackTrace();
                    SendMessage message = new SendMessage();
                    message.setChatId(chatId);
                    message.setText("Ошибка при сохранении данных в базу: " + e.getMessage() + ". Попробуйте снова.");
                    execute(message);
                    return;
                }
            } else {
                // Если это существующий пользователь, обновляем только email
                try (Connection conn = DriverManager.getConnection("jdbc:sqlite:users.db")) {
                    PreparedStatement pstmt = conn.prepareStatement("UPDATE users SET email = ? WHERE chat_id = ?");
                    pstmt.setString(1, data.email);
                    pstmt.setLong(2, chatId);
                    pstmt.executeUpdate();

                    System.out.println("Email обновлен в базе данных для chatId " + chatId);
                } catch (SQLException e) {
                    e.printStackTrace();
                    SendMessage message = new SendMessage();
                    message.setChatId(chatId);
                    message.setText("Ошибка при обновлении email: " + e.getMessage() + ". Попробуйте снова.");
                    execute(message);
                    return;
                }
            }

            // Очищаем состояние и данные
            userStates.remove(chatId);
            userData.remove(chatId);
            verificationCodes.remove(chatId);

            // Показываем меню
            SendMessage message = new SendMessage();
            message.setChatId(chatId);
            message.setText("Email успешно подтвержден! Выберите меню:");
            message.setReplyMarkup(getMenuKeyboard(chatId));
            execute(message);

            System.out.println("Меню отправлено пользователю с chatId " + chatId);

        } catch (TelegramApiException e) {
            e.printStackTrace();
            SendMessage errorMessage = new SendMessage();
            errorMessage.setChatId(chatId);
            errorMessage.setText("Ошибка при отправке сообщения: " + e.getMessage() + ". Попробуйте снова.");
            execute(errorMessage);
        }
    }

    private void handleHemisLoginInput(long chatId, String login) throws TelegramApiException {
        if (checkHemisLoginExists(login)) {
            SendMessage message = new SendMessage();
            message.setChatId(chatId);
            message.setText("Этот логин уже занят. Пожалуйста, выберите другой логин:");
            execute(message);
            return;
        }

        UserData data = userData.get(chatId);
        if (data != null) {
            data.hemisLogin = login;
            userStates.put(chatId, STATE_WAITING_HEMIS_PASSWORD);

            SendMessage message = new SendMessage();
            message.setChatId(chatId);
            message.setText("Введите пароль для HEMIS:");
            execute(message);
        }
    }

    private void handleHemisPasswordInput(long chatId, String password) throws TelegramApiException {
        UserData data = userData.get(chatId);
        if (data != null) {
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:users.db")) {
                // Сохраняем учетную запись HEMIS
                PreparedStatement pstmt = conn.prepareStatement(
                        "INSERT INTO hemis_credentials (chat_id, hemis_login, hemis_password) VALUES (?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                pstmt.setLong(1, chatId);
                pstmt.setString(2, data.hemisLogin);
                pstmt.setString(3, password);
                pstmt.executeUpdate();

                // Получаем сгенерированный ID учетной записи HEMIS
                ResultSet generatedKeys = pstmt.getGeneratedKeys();
                long hemisId = -1;
                if (generatedKeys.next()) {
                    hemisId = generatedKeys.getLong(1);
                }

                // Связываем учетную запись HEMIS с текущим Telegram-аккаунтом
                if (hemisId != -1) {
                    PreparedStatement linkStmt = conn.prepareStatement(
                            "INSERT INTO hemis_credentials_telegram (hemis_id, telegram_id) VALUES (?, ?)");
                    linkStmt.setLong(1, hemisId);
                    linkStmt.setLong(2, chatId);
                    linkStmt.executeUpdate();
                }
            } catch (SQLException e) {
                e.printStackTrace();
                SendMessage message = new SendMessage();
                message.setChatId(chatId);
                message.setText("Ошибка при создании учетной записи HEMIS: " + e.getMessage());
                execute(message);
                return;
            }

            userStates.remove(chatId);
            userData.remove(chatId);

            SendMessage message = new SendMessage();
            message.setChatId(chatId);
            message.setText("Учетные данные HEMIS успешно созданы! Теперь вы можете войти с любого аккаунта, используя этот логин и пароль.");
            message.setReplyMarkup(getMenuKeyboard(chatId));
            execute(message);
        }
    }

    private void handleLoginCredentialsInput(long chatId, String login) throws TelegramApiException {
        UserData data = userData.get(chatId);
        if (data != null) {
            data.hemisLogin = login;
            userStates.put(chatId, STATE_WAITING_PASSWORD_CREDENTIALS);

            SendMessage message = new SendMessage();
            message.setChatId(chatId);
            message.setText("Введите пароль для входа:");
            execute(message);
        }
    }

    private void handlePasswordCredentialsInput(long chatId, String password) throws TelegramApiException {
        UserData data = userData.get(chatId);
        if (data != null) {
            if (verifyHemisCredentials(data.hemisLogin, password)) {
                try {
                    linkHemisAccount(chatId, data.hemisLogin);
                    String userDetails = getUserDetails(data.hemisLogin);

                    SendMessage message = new SendMessage();
                    message.setChatId(chatId);
                    message.setText("Вы успешно вошли в свой профиль HEMIS!\n\n" + userDetails);
                    message.setReplyMarkup(getMenuKeyboard(chatId));
                    execute(message);
                } catch (SQLException e) {
                    SendMessage message = new SendMessage();
                    message.setChatId(chatId);
                    message.setText(e.getMessage());
                    message.setReplyMarkup(getUserMenuKeyboard());
                    execute(message);
                }
            } else {
                SendMessage message = new SendMessage();
                message.setChatId(chatId);
                message.setText("Неверный логин или пароль. Попробуйте снова:");
                message.setReplyMarkup(getUserMenuKeyboard());
                execute(message);
            }
            userStates.remove(chatId);
            userData.remove(chatId);
        }
    }

    private void handleBroadcastMessageInput(long chatId, String messageText) throws TelegramApiException {
        userStates.remove(chatId);
        broadcastMessage(messageText);

        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText("Сообщение успешно отправлено всем пользователям!");
        message.setReplyMarkup(getAdminMenuKeyboard());
        execute(message);
    }

    private void handleUserIdInput(long chatId, String userId) throws TelegramApiException {
        UserData data = userData.get(chatId);
        if (data != null) {
            long targetChatId = getChatIdByUserId(userId);
            if (targetChatId == -1) {
                SendMessage message = new SendMessage();
                message.setChatId(chatId);
                message.setText("Пользователь с ID " + userId + " не найден. Попробуйте снова:");
                execute(message);
                return;
            }

            data.targetUserId = userId;
            userStates.put(chatId, STATE_WAITING_PRIVATE_MESSAGE);

            SendMessage message = new SendMessage();
            message.setChatId(chatId);
            message.setText("Введите сообщение для пользователя с ID " + userId + ":");
            execute(message);
        }
    }

    private void handlePrivateMessageInput(long chatId, String messageText) throws TelegramApiException {
        UserData data = userData.get(chatId);
        if (data != null) {
            long targetChatId = getChatIdByUserId(data.targetUserId);
            if (targetChatId != -1) {
                SendMessage message = new SendMessage();
                message.setChatId(targetChatId);
                message.setText("Личное сообщение от администратора:\n" + messageText);
                try {
                    execute(message);
                } catch (TelegramApiException e) {
                    System.err.println("Не удалось отправить сообщение пользователю с ID " + data.targetUserId + ": " + e.getMessage());
                }

                SendMessage confirmation = new SendMessage();
                confirmation.setChatId(chatId);
                confirmation.setText("Сообщение успешно отправлено пользователю с ID " + data.targetUserId + "!");
                confirmation.setReplyMarkup(getAdminMenuKeyboard());
                execute(confirmation);
            } else {
                SendMessage message = new SendMessage();
                message.setChatId(chatId);
                message.setText("Пользователь с ID " + data.targetUserId + " не найден.");
                message.setReplyMarkup(getAdminMenuKeyboard());
                execute(message);
            }
            userStates.remove(chatId);
            userData.remove(chatId);
        }
    }

    private void handleMenuSelection(long chatId, String text) throws TelegramApiException {
        if (!isUserRegistered(chatId)) {
            UserData data = new UserData();
            data.isAdmin = false;
            userData.put(chatId, data);
            userStates.put(chatId, STATE_WAITING_NAME);

            System.out.println("Создан новый UserData для chatId " + chatId + " в handleMenuSelection");

            SendMessage message = new SendMessage();
            message.setChatId(chatId);
            message.setText("Пожалуйста, напишите ваше имя и фамилию");
            ReplyKeyboardRemove removeKeyboard = new ReplyKeyboardRemove();
            removeKeyboard.setRemoveKeyboard(true);
            message.setReplyMarkup(removeKeyboard);
            execute(message);
            return;
        }

        if (!hasEmail(chatId)) {
            UserData data = userData.get(chatId);
            if (data == null) {
                data = new UserData();
                try (Connection conn = DriverManager.getConnection("jdbc:sqlite:users.db")) {
                    PreparedStatement pstmt = conn.prepareStatement("SELECT first_name, last_name, phone_number FROM users WHERE chat_id = ?");
                    pstmt.setLong(1, chatId);
                    ResultSet rs = pstmt.executeQuery();
                    if (rs.next()) {
                        data.name = rs.getString("first_name") + " " + rs.getString("last_name");
                        data.phoneNumber = rs.getString("phone_number");
                        data.isAdmin = isAdmin(chatId);
                    }
                } catch (SQLException e) {
                    e.printStackTrace();
                }
                userData.put(chatId, data);
                System.out.println("Восстановлены данные для chatId " + chatId + " в handleMenuSelection: имя = " + data.name + ", телефон = " + data.phoneNumber);
            }
            userStates.put(chatId, STATE_WAITING_EMAIL);

            SendMessage emailRequest = new SendMessage();
            emailRequest.setChatId(chatId);
            emailRequest.setText("Пожалуйста, укажите ваш адрес электронной почты:");
            ReplyKeyboardRemove removeKeyboard = new ReplyKeyboardRemove();
            removeKeyboard.setRemoveKeyboard(true);
            emailRequest.setReplyMarkup(removeKeyboard);
            execute(emailRequest);
            return;
        }

        if (text.equals("Админ меню")) {
            if (isAdmin(chatId)) {
                SendMessage message = new SendMessage();
                message.setChatId(chatId);
                message.setText("Админ меню:");
                message.setReplyMarkup(getAdminMenuKeyboard());
                execute(message);
            } else if (!hasAdmin()) {
                try (Connection conn = DriverManager.getConnection("jdbc:sqlite:users.db")) {
                    PreparedStatement pstmt = conn.prepareStatement("UPDATE users SET is_admin = 1 WHERE chat_id = ?");
                    pstmt.setLong(1, chatId);
                    pstmt.executeUpdate();
                } catch (SQLException e) {
                    e.printStackTrace();
                }

                SendMessage message = new SendMessage();
                message.setChatId(chatId);
                message.setText("Поздравляем! Вы стали администратором. Админ меню:");
                message.setReplyMarkup(getAdminMenuKeyboard());
                execute(message);
            } else {
                SendMessage message = new SendMessage();
                message.setChatId(chatId);
                message.setText("Администратор уже назначен. У вас нет прав для доступа к админ-меню.");
                message.setReplyMarkup(getMenuKeyboard(chatId));
                execute(message);
            }
        } else if (text.equals("Пользовательское меню")) {
            SendMessage message = new SendMessage();
            message.setChatId(chatId);
            message.setText("Добро пожаловать в пользовательское меню!");
            message.setReplyMarkup(getUserMenuKeyboard());
            execute(message);
        } else if (text.equals("Создать учетные данные HEMIS")) {
            UserData data = new UserData();
            userData.put(chatId, data);
            userStates.put(chatId, STATE_WAITING_HEMIS_LOGIN);

            SendMessage message = new SendMessage();
            message.setChatId(chatId);
            message.setText("Введите логин для HEMIS:");
            ReplyKeyboardRemove removeKeyboard = new ReplyKeyboardRemove();
            removeKeyboard.setRemoveKeyboard(true);
            message.setReplyMarkup(removeKeyboard);
            execute(message);
        } else if (text.equals("Войти в HEMIS")) {
            UserData data = new UserData();
            userData.put(chatId, data);
            userStates.put(chatId, STATE_WAITING_LOGIN_CREDENTIALS);

            SendMessage message = new SendMessage();
            message.setChatId(chatId);
            message.setText("Введите ваш логин HEMIS:");
            ReplyKeyboardRemove removeKeyboard = new ReplyKeyboardRemove();
            removeKeyboard.setRemoveKeyboard(true);
            message.setReplyMarkup(removeKeyboard);
            execute(message);
        } else if (text.equals("Удалить email")) {
            try {
                deleteEmail(chatId);
                UserData data = new UserData();
                try (Connection conn = DriverManager.getConnection("jdbc:sqlite:users.db")) {
                    PreparedStatement pstmt = conn.prepareStatement("SELECT first_name, last_name, phone_number FROM users WHERE chat_id = ?");
                    pstmt.setLong(1, chatId);
                    ResultSet rs = pstmt.executeQuery();
                    if (rs.next()) {
                        data.name = rs.getString("first_name") + " " + rs.getString("last_name");
                        data.phoneNumber = rs.getString("phone_number");
                        data.isAdmin = isAdmin(chatId);
                    }
                } catch (SQLException e) {
                    e.printStackTrace();
                    SendMessage message = new SendMessage();
                    message.setChatId(chatId);
                    message.setText("Ошибка при удалении email: " + e.getMessage());
                    message.setReplyMarkup(getUserMenuKeyboard());
                    execute(message);
                    return;
                }
                userData.put(chatId, data);
                userStates.put(chatId, STATE_WAITING_EMAIL);

                SendMessage message = new SendMessage();
                message.setChatId(chatId);
                message.setText("Email удален. Пожалуйста, укажите новый адрес электронной почты:");
                ReplyKeyboardRemove removeKeyboard = new ReplyKeyboardRemove();
                removeKeyboard.setRemoveKeyboard(true);
                message.setReplyMarkup(removeKeyboard);
                execute(message);
            } catch (SQLException e) {
                e.printStackTrace();
                SendMessage message = new SendMessage();
                message.setChatId(chatId);
                message.setText("Ошибка при удалении email: " + e.getMessage());
                message.setReplyMarkup(getUserMenuKeyboard());
                execute(message);
            }
        } else if (text.equals("Список пользователей") && isAdmin(chatId)) {
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:users.db")) {
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT user_id, chat_id, first_name, last_name, phone_number, email, is_admin, registration_time FROM users");

                // Определяем максимальные длины для каждого столбца
                int maxIdLength = "ID".length();
                int maxFirstNameLength = "Имя".length();
                int maxLastNameLength = "Фамилия".length();
                int maxPhoneLength = "Телефон".length();
                int maxEmailLength = "Email".length();
                int maxRegTimeLength = "Время регистрации".length();

                List<String[]> userRows = new ArrayList<>();
                while (rs.next()) {
                    String userId = rs.getString("user_id");
                    String firstName = rs.getString("first_name");
                    String lastName = rs.getString("last_name");
                    String phoneNumber = rs.getString("phone_number");
                    String email = rs.getString("email") != null ? rs.getString("email") : "Не указано";
                    String registrationTime = rs.getString("registration_time") != null ? rs.getString("registration_time") : "Не указано";
                    boolean isAdmin = rs.getInt("is_admin") == 1;

                    String displayName = isAdmin ? firstName + " (админ)" : firstName;

                    // Обрезаем длинные строки
                    if (email.length() > 20) {
                        email = email.substring(0, 17) + "...";
                    }
                    if (displayName.length() > 15) {
                        displayName = displayName.substring(0, 12) + "...";
                    }
                    if (lastName.length() > 15) {
                        lastName = lastName.substring(0, 12) + "...";
                    }

                    // Обновляем максимальные длины
                    maxIdLength = Math.max(maxIdLength, userId.length());
                    maxFirstNameLength = Math.max(maxFirstNameLength, displayName.length());
                    maxLastNameLength = Math.max(maxLastNameLength, lastName.length());
                    maxPhoneLength = Math.max(maxPhoneLength, phoneNumber.length());
                    maxEmailLength = Math.max(maxEmailLength, email.length());
                    maxRegTimeLength = Math.max(maxRegTimeLength, registrationTime.length());

                    userRows.add(new String[]{userId, displayName, lastName, phoneNumber, email, registrationTime});
                }

                // Формируем таблицу
                StringBuilder table = new StringBuilder("```\n");
                String headerFormat = "| %-" + maxIdLength + "s | %-" + maxFirstNameLength + "s | %-" + maxLastNameLength + "s | %-" + maxPhoneLength + "s | %-" + maxEmailLength + "s | %-" + maxRegTimeLength + "s |\n";
                table.append(String.format(headerFormat, "ID", "Имя", "Фамилия", "Телефон", "Email", "Время регистрации"));

                // Разделительная линия
                StringBuilder separator = new StringBuilder("|");
                separator.append("-".repeat(maxIdLength + 2)).append("|");
                separator.append("-".repeat(maxFirstNameLength + 2)).append("|");
                separator.append("-".repeat(maxLastNameLength + 2)).append("|");
                separator.append("-".repeat(maxPhoneLength + 2)).append("|");
                separator.append("-".repeat(maxEmailLength + 2)).append("|");
                separator.append("-".repeat(maxRegTimeLength + 2)).append("|");
                table.append(separator.toString()).append("\n");

                // Строки с данными
                String rowFormat = "| %-" + maxIdLength + "s | %-" + maxFirstNameLength + "s | %-" + maxLastNameLength + "s | %-" + maxPhoneLength + "s | %-" + maxEmailLength + "s | %-" + maxRegTimeLength + "s |\n";
                for (String[] row : userRows) {
                    table.append(String.format(rowFormat, row[0], row[1], row[2], row[3], row[4], row[5]));
                }
                table.append("```");

                SendMessage message = new SendMessage();
                message.setChatId(chatId);
                message.setParseMode("Markdown");
                if (!userRows.isEmpty()) {
                    message.setText("Список пользователей:\n" + table.toString());
                } else {
                    message.setText("Нет зарегистрированных пользователей");
                }
                message.setReplyMarkup(getAdminMenuKeyboard());
                execute(message);
            } catch (SQLException e) {
                e.printStackTrace();
            }
        } else if (text.equals("Опубликовать сообщение") && isAdmin(chatId)) {
            userStates.put(chatId, STATE_WAITING_BROADCAST_MESSAGE);

            SendMessage message = new SendMessage();
            message.setChatId(chatId);
            message.setText("Введите сообщение, которое хотите отправить всем пользователям:");
            ReplyKeyboardRemove removeKeyboard = new ReplyKeyboardRemove();
            removeKeyboard.setRemoveKeyboard(true);
            message.setReplyMarkup(removeKeyboard);
            execute(message);
        } else if (text.equals("Обратиться к пользователю") && isAdmin(chatId)) {
            UserData data = new UserData();
            userData.put(chatId, data);
            userStates.put(chatId, STATE_WAITING_USER_ID);

            SendMessage message = new SendMessage();
            message.setChatId(chatId);
            message.setText("Введите ID пользователя, которому хотите отправить сообщение:");
            ReplyKeyboardRemove removeKeyboard = new ReplyKeyboardRemove();
            removeKeyboard.setRemoveKeyboard(true);
            message.setReplyMarkup(removeKeyboard);
            execute(message);
        }
    }

    private ReplyKeyboardMarkup getMenuKeyboard(long chatId) {
        KeyboardRow row = new KeyboardRow();
        if (isAdmin(chatId)) {
            row.add("Админ меню");
            row.add("Пользовательское меню");
        } else if (!hasAdmin()) {
            row.add("Админ меню");
            row.add("Пользовательское меню");
        } else {
            row.add("Пользовательское меню");
        }
        List<KeyboardRow> keyboard = new ArrayList<>();
        keyboard.add(row);

        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup();
        markup.setKeyboard(keyboard);
        markup.setResizeKeyboard(true);
        return markup;
    }

    private ReplyKeyboardMarkup getUserMenuKeyboard() {
        KeyboardRow row1 = new KeyboardRow();
        row1.add("Создать учетные данные HEMIS");
        row1.add("Войти в HEMIS");
        KeyboardRow row2 = new KeyboardRow();
        row2.add("Удалить email");
        List<KeyboardRow> keyboard = new ArrayList<>();
        keyboard.add(row1);
        keyboard.add(row2);

        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup();
        markup.setKeyboard(keyboard);
        markup.setResizeKeyboard(true);
        return markup;
    }

    private ReplyKeyboardMarkup getAdminMenuKeyboard() {
        KeyboardRow row1 = new KeyboardRow();
        row1.add("Список пользователей");
        row1.add("Опубликовать сообщение");
        KeyboardRow row2 = new KeyboardRow();
        row2.add("Обратиться к пользователю");
        List<KeyboardRow> keyboard = new ArrayList<>();
        keyboard.add(row1);
        keyboard.add(row2);

        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup();
        markup.setKeyboard(keyboard);
        markup.setResizeKeyboard(true);
        return markup;
    }
}