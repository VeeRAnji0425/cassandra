/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.streaming;

import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.io.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.db.Table;
import org.apache.cassandra.io.sstable.Descriptor;
import org.apache.cassandra.io.sstable.SSTableReader;
import org.apache.cassandra.io.sstable.SSTableWriter;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.utils.Pair;

public class IncomingStreamReader
{
    private static final Logger logger = LoggerFactory.getLogger(IncomingStreamReader.class);

    private final PendingFile localFile;
    private final PendingFile remoteFile;
    private final SocketChannel socketChannel;
    private final StreamInSession session;

    public IncomingStreamReader(StreamHeader header, SocketChannel socketChannel) throws IOException
    {
        this.socketChannel = socketChannel;
        InetSocketAddress remoteAddress = (InetSocketAddress)socketChannel.socket().getRemoteSocketAddress();

        session = StreamInSession.get(remoteAddress.getAddress(), header.sessionId);
        session.addFiles(header.pendingFiles);
        session.setTable(header.table);
        // pendingFile gets the new context for the local node.
        remoteFile = header.file;
        localFile = remoteFile != null ? StreamIn.getContextMapping(remoteFile) : null;
    }

    public void read() throws IOException
    {
        if (remoteFile != null)
            readFile();

        session.closeIfFinished();
    }

    private void readFile() throws IOException
    {
        if (logger.isDebugEnabled())
        {
            logger.debug("Receiving stream");
            logger.debug("Creating file for {}", localFile.getFilename());
        }
        FileOutputStream fos = new FileOutputStream(localFile.getFilename(), true);
        FileChannel fc = fos.getChannel();

        long offset = 0;
        try
        {
            for (Pair<Long, Long> section : localFile.sections)
            {
                long length = section.right - section.left;
                long bytesRead = 0;
                while (bytesRead < length)
                    bytesRead += fc.transferFrom(socketChannel, offset + bytesRead, length - bytesRead);
                offset += length;
            }
        }
        catch (IOException ex)
        {
            logger.debug("Receiving stream: recovering from IO error");
            /* Ask the source node to re-stream this file. */
            handleFileStatus(FileStatus.Action.RETRY);

            /* Delete the orphaned file. */
            FileUtils.deleteWithConfirm(new File(localFile.getFilename()));
            throw ex;
        }
        finally
        {
            fc.close();
        }

        if (logger.isDebugEnabled())
            logger.debug("Removing stream context {}", remoteFile);
        handleFileStatus(FileStatus.Action.FINISHED);
    }

    private void handleFileStatus(FileStatus.Action action) throws IOException
    {
        FileStatus status = new FileStatus(remoteFile.getFilename(), session.getSessionId(), action);

        if (FileStatus.Action.RETRY == action)
        {
            // file needs to be restreamed
            logger.warn("Streaming of file {} from {} failed: requesting a retry.", remoteFile, session);
            MessagingService.instance.sendOneWay(status.makeStreamStatusMessage(), session.getHost());
            return;
        }

        assert FileStatus.Action.FINISHED == action : "Unknown stream action: " + action;

        addSSTable(localFile);
        session.remove(remoteFile);
        // send a StreamStatus message telling the source node it can delete this file
        if (logger.isDebugEnabled())
            logger.debug("Sending a streaming finished message for {} to {}", remoteFile, session);
        MessagingService.instance.sendOneWay(status.makeStreamStatusMessage(), session.getHost());
    }

    public static void addSSTable(PendingFile pendingFile)
    {
        // file was successfully streamed
        Descriptor desc = pendingFile.desc;
        try
        {
            SSTableReader sstable = SSTableWriter.recoverAndOpen(pendingFile.desc);
            Table.open(desc.ksname).getColumnFamilyStore(desc.cfname).addSSTable(sstable);
            logger.info("Streaming added " + sstable);
        }
        catch (IOException e)
        {
            logger.error("Failed adding {}", pendingFile, e);
            throw new RuntimeException("Not able to add streamed file " + pendingFile.getFilename(), e);
        }
    }
}
