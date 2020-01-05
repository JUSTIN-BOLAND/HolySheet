package com.uddernetworks.holysheet;

import com.google.api.services.drive.Drive;
import com.google.api.services.sheets.v4.Sheets;
import com.uddernetworks.holysheet.command.CommandHandler;
import com.uddernetworks.holysheet.socket.SocketCommunication;
import com.uddernetworks.holysheet.socket.jshell.JShellRemote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.IOException;
import java.security.GeneralSecurityException;

public class HolySheet {
    private static final Logger LOGGER = LoggerFactory.getLogger(HolySheet.class);

    private AuthManager authManager;
    private SheetManager sheetManager;
    private SocketCommunication socketCommunication;
    private JShellRemote jShellRemote;
    private Drive drive;
    private Sheets sheets;

    public static void main(String[] args) {
        new HolySheet().start(args);
    }

    private void start(String[] args) {
        if (args.length == 0) {
            args = new String[]{"-h"};
        }

        System.exit(new CommandLine(new CommandHandler(this)).execute(args));
    }

    public void init(String credentialPath) {
        try {
            LOGGER.info("Initializing everything...");

            authManager = new AuthManager(credentialPath);
            authManager.initialize();
            drive = authManager.getDrive();
            sheets = authManager.getSheets();

            sheetManager = new SheetManager(this);
            socketCommunication = new SocketCommunication(this);

            jShellRemote = new JShellRemote(socketCommunication);

            sheetManager.init();
        } catch (GeneralSecurityException | IOException e) {
            LOGGER.error("Error initializing", e);
        }
    }

    public AuthManager getAuthManager() {
        return authManager;
    }

    public SheetManager getSheetManager() {
        return sheetManager;
    }

    public SocketCommunication getSocketCommunication() {
        return socketCommunication;
    }

    public JShellRemote getjShellRemote() {
        return jShellRemote;
    }

    public Drive getDrive() {
        return drive;
    }

    public Sheets getSheets() {
        return sheets;
    }
}