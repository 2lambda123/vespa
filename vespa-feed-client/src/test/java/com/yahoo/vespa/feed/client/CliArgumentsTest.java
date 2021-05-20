package com.yahoo.vespa.feed.client;// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

import com.yahoo.vespa.feed.client.CliArguments.CliArgumentsException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author bjorncs
 */
class CliArgumentsTest {

    @Test
    void parses_parameters_correctly() throws CliArgumentsException {
        CliArguments args = CliArguments.fromRawArgs(new String[]{
                "--endpoint=https://vespa.ai:4443/", "--file=feed.json", "--connections=10",
                "--max-streams-per-connection=128", "--certificate=cert.pem", "--key=key.pem",
                "--ca-certificates=ca-certs.pem", "--disable-ssl-hostname-verification"});
        assertEquals(URI.create("https://vespa.ai:4443/"), args.endpoint());
        assertEquals(Paths.get("feed.json"), args.inputFile());
        assertEquals(10, args.connections().getAsInt());
        assertEquals(128, args.maxStreamsPerConnection().getAsInt());
        assertEquals(Paths.get("cert.pem"), args.certificateAndKey().get().certificateFile);
        assertEquals(Paths.get("key.pem"), args.certificateAndKey().get().privateKeyFile);
        assertEquals(Paths.get("ca-certs.pem"), args.caCertificates().get());
        assertTrue(args.sslHostnameVerificationDisabled());
        assertFalse(args.helpSpecified());
    }

    @Test
    void fails_on_missing_parameters() throws CliArgumentsException {
        CliArguments cliArguments = CliArguments.fromRawArgs(new String[0]);
        CliArgumentsException exception =  assertThrows(CliArgumentsException.class, cliArguments::endpoint);
        assertEquals("Endpoint must be specified", exception.getMessage());
        exception =  assertThrows(CliArgumentsException.class, cliArguments::inputFile);
        assertEquals("Feed file must be specified", exception.getMessage());
    }

    @Test
    void generated_help_page_contains_expected_description() throws CliArgumentsException, IOException {
        CliArguments args = CliArguments.fromRawArgs(new String[]{"--help"});
        assertTrue(args.helpSpecified());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        args.printHelp(out);
        String text = out.toString();
        String expectedHelp = new String(Files.readAllBytes(Paths.get("src", "test", "resources", "help.txt")));
        assertEquals(expectedHelp, text);
    }

}