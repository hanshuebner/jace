/*
 * Copyright (C) 2012 Brendan Robert (BLuRry) brendan.robert@gmail.com.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301  USA
 */
package jace.hardware;

import jace.config.Name;
import jace.core.Card;
import jace.core.Computer;
import jace.core.RAMEvent;
import jace.core.RAMEvent.TYPE;

import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Apple2-IO-RPi implementation
 *
 * @author hans.huebner@gmail.com (Hans Hübner)
 */
@Name("Apple2-IO-RPi")
public class CardApple2IORPi extends Card {

    final int RECEIVE_FROM_PORT = 22129;
    final int SEND_TO_PORT = 22130;
    final InetAddress address;

    final int inputByteReg = 0x0e;
    final int outputByteReg = 0x0d;
    final int inputFlagsReg = 0x0b;
    final int outputFlagsReg = 0x07;

    final int outputFlagSendRequestMask = 0x01; // GPIO23
    final int outputFlagReceiveConfirmMask = 0x02; // GPIO18

    final int inputFlagReceiveRequestMask = 0x80; // GPIO24
    final int inputFlagSendConfirmMask = 0x40; // GPIO25

    int outputFlagsValue;
    int inputFlagsValue;

    BlockingQueue<Byte> receiveQueue = new LinkedBlockingQueue<>(512);

    final private DatagramSocket socket;
    Logger logger;

    final int debugIo  = 0x01;
    final int debugMem = 0x02;
    final int debugLogical = 0x04;
    int debug = 0;

    static String romPath = "jace/data/apple2-io-rpi.rom";

    public CardApple2IORPi(Computer computer) throws SocketException, UnknownHostException {
        super(computer);
        reset();
        logger = Logger.getLogger(CardApple2IORPi.class.getName());
        try {
            loadRom(romPath);
        } catch (Exception ex) {
            logger.log(Level.SEVERE, "Could not load ROM " + romPath, ex);
        }
        socket = new DatagramSocket(RECEIVE_FROM_PORT);
        address = InetAddress.getByName("localhost");
        Thread thread = new Thread(() -> {
            byte[] buf = new byte[256];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(packet);
                    byte [] data = packet.getData();
                    for (int i = 0; i < packet.getLength(); i++) {
                        if (!receiveQueue.offer(data[i])) {
                            logger.warning("could not enqueue received byte - queue is full");
                        }
                    }
                } catch (IOException e) {
                    logger.warning("Could not receive packet " + e);
                }
            }
        });
        thread.start();
    }

    @Override
    public void reset() {
        receiveQueue.clear();
        outputFlagsValue = 0xff;
        inputFlagsValue = 0xff & ~inputFlagSendConfirmMask;
    }

    @Override
    protected void handleIOAccess(int register, TYPE type, int value, RAMEvent e) {
        if (type == TYPE.READ_DATA) {
            if (register == inputFlagsReg) {
                e.setNewValue(inputFlagsValue);
                if (!receiveQueue.isEmpty()) {
                    inputFlagsValue &= ~inputFlagReceiveRequestMask;
                }
                if ((inputFlagsValue & inputFlagSendConfirmMask) == 0) {
                    inputFlagsValue |= inputFlagSendConfirmMask;
                } else {
                    inputFlagsValue &= ~inputFlagSendConfirmMask;
                }
                if ((debug & debugLogical) != 0) {
                    System.out.printf("RR     IR: %d IC: %d OR: %d OC: %d\n",
                            (inputFlagsValue & inputFlagReceiveRequestMask) == 0 ? 1 : 0,
                            (inputFlagsValue & inputFlagSendConfirmMask) == 0 ? 1 : 0,
                            (outputFlagsValue & outputFlagSendRequestMask) == 0 ? 1 : 0,
                            (outputFlagsValue & outputFlagReceiveConfirmMask) == 0 ? 1 : 0);
                }
            } else if (register == inputByteReg) {
                try {
                    e.setNewValue(receiveQueue.take());
                } catch (InterruptedException ex) {
                    logger.warning("cannot receive - queue operation was interrupted");
                }
                inputFlagsValue |= inputFlagReceiveRequestMask;
                if ((debug & debugLogical) != 0) {
                    System.out.printf("<= $%02x IR: %d IC: %d OR: %d OC: %d\n",
                            Byte.toUnsignedInt((byte) e.getNewValue()),
                            (inputFlagsValue & inputFlagReceiveRequestMask) == 0 ? 1 : 0,
                            (inputFlagsValue & inputFlagSendConfirmMask) == 0 ? 1 : 0,
                            (outputFlagsValue & outputFlagSendRequestMask) == 0 ? 1 : 0,
                            (outputFlagsValue & outputFlagReceiveConfirmMask) == 0 ? 1 : 0);
                }
            }
        } else if (type == TYPE.WRITE) {
            if (register == outputByteReg) {
                byte[] buf = {(byte) e.getNewValue()};
                DatagramPacket packet = new DatagramPacket(buf, buf.length, address, SEND_TO_PORT);
                try {
                    socket.send(packet);
                } catch (IOException ex) {
                    logger.warning("Could not send packet: " + e);
                }
                inputFlagsValue &= ~inputFlagSendConfirmMask;
                if ((debug & debugLogical) != 0) {
                    System.out.printf("=> $%02x IR: %d IC: %d OR: %d OC: %d\n",
                            Byte.toUnsignedInt((byte) e.getNewValue()),
                            (inputFlagsValue & inputFlagReceiveRequestMask) == 0 ? 1 : 0,
                            (inputFlagsValue & inputFlagSendConfirmMask) == 0 ? 1 : 0,
                            (outputFlagsValue & outputFlagSendRequestMask) == 0 ? 1 : 0,
                            (outputFlagsValue & outputFlagReceiveConfirmMask) == 0 ? 1 : 0);
                }
            } else if (register == outputFlagsReg) {
                outputFlagsValue = e.getNewValue();
                if ((outputFlagsValue & outputFlagReceiveConfirmMask) == 0) {
                    inputFlagsValue |= inputFlagSendConfirmMask;
                } else {
                    inputFlagsValue &= ~inputFlagSendConfirmMask;
                }
                if ((debug & debugLogical) != 0) {
                    System.out.printf("WW     IR: %d IC: %d OR: %d OC: %d\n",
                            (inputFlagsValue & inputFlagReceiveRequestMask) == 0 ? 1 : 0,
                            (inputFlagsValue & inputFlagSendConfirmMask) == 0 ? 1 : 0,
                            (outputFlagsValue & outputFlagSendRequestMask) == 0 ? 1 : 0,
                            (outputFlagsValue & outputFlagReceiveConfirmMask) == 0 ? 1 : 0);
                }
            }
        }
        if ((debug & debugIo) != 0) {
            System.out.printf("IO  %12s $%02X => $%02X%n", e.getType(), register, Byte.toUnsignedInt((byte) e.getNewValue()));
        }
    }

    @Override
    protected void handleFirmwareAccess(int offset, TYPE type, int value, RAMEvent e) {
        if ((debug & debugMem) != 0) {
            System.out.printf("ROM %12s $%04X => $%02X%n", e.getType(), e.getAddress(), Byte.toUnsignedInt((byte) value));
        }
    }

    @Override
    protected void handleC8FirmwareAccess(int register, TYPE type, int value, RAMEvent e) {
    }

    @Override
    protected String getDeviceName() {
        return "Apple2-IO-RPi";
    }

    @Override
    public void tick() {
    }

    public void loadRom(String path) throws IOException {
        InputStream romFile = CardApple2IORPi.class.getClassLoader().getResourceAsStream(path);
        assert romFile != null;
        if (romFile.skip(0x700) != 0x700) {
            logger.warning("cannot skip to ROM contents");
        }
        final int cxRomLength = 0x0100;
        byte[] romxData = new byte[cxRomLength];
        if (romFile.read(romxData) != cxRomLength) {
            throw new IOException("Bad ROM size");
        }
        getCxRom().loadData(romxData);
        romFile.close();
        final int c8RomLength = 0x0700;
        romFile = CardApple2IORPi.class.getClassLoader().getResourceAsStream(path);
        byte[] rom8Data = new byte[c8RomLength];
        assert romFile != null;
        if (romFile.read(rom8Data) != c8RomLength) {
            throw new IOException("Bad ROM size");
        }
        getC8Rom().loadData(rom8Data);
        romFile.close();
    }
}
