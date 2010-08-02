package org.apache.cassandra.db.columniterator;
/*
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * 
 */


import java.util.*;
import java.io.IOError;
import java.io.IOException;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.IColumn;
import org.apache.cassandra.db.ColumnFamily;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.io.sstable.IndexHelper;
import org.apache.cassandra.io.sstable.SSTableReader;
import org.apache.cassandra.io.util.FileDataInput;
import org.apache.cassandra.io.util.FileMark;

import com.google.common.collect.AbstractIterator;
import org.apache.cassandra.utils.FBUtilities;

/**
 *  A Column Iterator over SSTable
 */
public class SSTableSliceIterator implements IColumnIterator
{
    private final boolean reversed;
    private final byte[] startColumn;
    private final byte[] finishColumn;
    private final AbstractType comparator;
    private IColumnIterator reader;
    private boolean closeFileWhenDone = false;
    private DecoratedKey decoratedKey;

    public SSTableSliceIterator(SSTableReader ssTable, DecoratedKey key, byte[] startColumn, byte[] finishColumn, boolean reversed)
    {
        this(ssTable, null, key, startColumn, finishColumn, reversed);
    }

    /**
     * An iterator for a slice within an SSTable
     * @param ssTable The SSTable to iterate over
     * @param file Optional parameter that input is read from.  If null is passed, this class creates an appropriate one automatically.
     * If this class creates, it will close the underlying file when #close() is called.
     * If a caller passes a non-null argument, this class will NOT close the underlying file when the iterator is closed (i.e. the caller is responsible for closing the file)
     * In all cases the caller should explicitly #close() this iterator.
     * @param key The key the requested slice resides under
     * @param startColumn The start of the slice
     * @param finishColumn The end of the slice
     * @param reversed Results are returned in reverse order iff reversed is true.
     */
    public SSTableSliceIterator(SSTableReader ssTable, FileDataInput file, DecoratedKey key, byte[] startColumn, byte[] finishColumn, boolean reversed)
    {
        this.reversed = reversed;
        this.comparator = ssTable.getColumnComparator();
        this.startColumn = startColumn;
        this.finishColumn = finishColumn;
        this.decoratedKey = key;

        if (file == null)
        {
            closeFileWhenDone = true; //if we create it, we close it
            file = ssTable.getFileDataInput(decoratedKey, DatabaseDescriptor.getSlicedReadBufferSizeInKB() * 1024);
            if (file == null)
                return;
            try
            {
                DecoratedKey keyInDisk = SSTableReader.decodeKey(ssTable.getPartitioner(),
                                                                 ssTable.getDescriptor(),
                                                                 FBUtilities.readShortByteArray(file));
                assert keyInDisk.equals(decoratedKey)
                       : String.format("%s != %s in %s", keyInDisk, decoratedKey, file.getPath());
                SSTableReader.readRowSize(file, ssTable.getDescriptor());
            }
            catch (IOException e)
            {
                throw new IOError(e);
            }
        }

        reader = startColumn.length == 0 && !reversed ? new SimpleColumnReader(ssTable, file) : new ColumnGroupReader(ssTable, file);
    }
    
    public DecoratedKey getKey()
    {
        return decoratedKey;
    }

    public ColumnFamily getColumnFamily() throws IOException
    {
        return reader == null ? null : reader.getColumnFamily();
    }

    public boolean hasNext()
    {
        return reader.hasNext();
    }

    public IColumn next()
    {
        return reader.next();
    }

    public void remove()
    {
        throw new UnsupportedOperationException();
    }

    public void close() throws IOException
    {
        if (reader != null)
            reader.close();
    }

    private class SimpleColumnReader extends AbstractIterator<IColumn> implements IColumnIterator
    {
        private final FileDataInput file;
        private final ColumnFamily emptyColumnFamily;
        private final int columns;
        private int i;
        private FileMark mark;

        public SimpleColumnReader(SSTableReader ssTable, FileDataInput input)
        {
            this.file = input;
            try
            {
                IndexHelper.skipBloomFilter(file);
                IndexHelper.skipIndex(file);

                emptyColumnFamily = ColumnFamily.serializer().deserializeFromSSTableNoColumns(ssTable.makeColumnFamily(), file);
                columns = file.readInt();
                mark = file.mark();
            }
            catch (IOException e)
            {
                throw new IOError(e);
            }
        }

        protected IColumn computeNext()
        {
            if (i++ >= columns)
                return endOfData();

            IColumn column;
            try
            {
                file.reset(mark);
                column = emptyColumnFamily.getColumnSerializer().deserialize(file);
            }
            catch (IOException e)
            {
                throw new RuntimeException("error reading " + i + " of " + columns, e);
            }
            if (finishColumn.length > 0 && comparator.compare(column.name(), finishColumn) > 0)
                return endOfData();

            mark = file.mark();
            return column;
        }

        public ColumnFamily getColumnFamily() throws IOException
        {
            return emptyColumnFamily;
        }

        public void close() throws IOException
        {
            file.close();
        }

        public DecoratedKey getKey()
        {
            throw new UnsupportedOperationException();
        }
    }

    /**
     *  This is a reader that finds the block for a starting column and returns
     *  blocks before/after it for each next call. This function assumes that
     *  the CF is sorted by name and exploits the name index.
     */
    class ColumnGroupReader extends AbstractIterator<IColumn> implements IColumnIterator
    {
        private final ColumnFamily emptyColumnFamily;

        private final List<IndexHelper.IndexInfo> indexes;
        private final FileDataInput file;

        private int curRangeIndex;
        private Deque<IColumn> blockColumns = new ArrayDeque<IColumn>();
        private final FileMark mark;

        public ColumnGroupReader(SSTableReader ssTable, FileDataInput input)
        {
            this.file = input;
            try
            {
                IndexHelper.skipBloomFilter(file);
                indexes = IndexHelper.deserializeIndex(file);
    
                emptyColumnFamily = ColumnFamily.serializer().deserializeFromSSTableNoColumns(ssTable.makeColumnFamily(), file);
                file.readInt(); // column count
            }
            catch (IOException e)
            {
                throw new IOError(e);
            }
            this.mark = file.mark();
            curRangeIndex = IndexHelper.indexFor(startColumn, indexes, comparator, reversed);
            if (reversed && curRangeIndex == indexes.size())
                curRangeIndex--;
        }

        public ColumnFamily getColumnFamily()
        {
            return emptyColumnFamily;
        }

        public DecoratedKey getKey()
        {
            throw new UnsupportedOperationException();
        }

        private boolean isColumnNeeded(IColumn column)
        {
            if (startColumn.length == 0 && finishColumn.length == 0)
                return true;
            else if (startColumn.length == 0 && !reversed)
                return comparator.compare(column.name(), finishColumn) <= 0;
            else if (startColumn.length == 0 && reversed)
                return comparator.compare(column.name(), finishColumn) >= 0;
            else if (finishColumn.length == 0 && !reversed)
                return comparator.compare(column.name(), startColumn) >= 0;
            else if (finishColumn.length == 0 && reversed)
                return comparator.compare(column.name(), startColumn) <= 0;
            else if (!reversed)
                return comparator.compare(column.name(), startColumn) >= 0 && comparator.compare(column.name(), finishColumn) <= 0;
            else // if reversed
                return comparator.compare(column.name(), startColumn) <= 0 && comparator.compare(column.name(), finishColumn) >= 0;
        }

        protected IColumn computeNext()
        {
            while (true)
            {
                IColumn column = blockColumns.poll();
                if (column != null && isColumnNeeded(column))
                    return column;
                try
                {
                    if (column == null && !getNextBlock())
                        return endOfData();
                }
                catch (IOException e)
                {
                    throw new RuntimeException(e);
                }
            }
        }

        public boolean getNextBlock() throws IOException
        {
            if (curRangeIndex < 0 || curRangeIndex >= indexes.size())
                return false;

            /* seek to the correct offset to the data, and calculate the data size */
            IndexHelper.IndexInfo curColPosition = indexes.get(curRangeIndex);

            /* see if this read is really necessary. */
            if (reversed)
            {
                if ((finishColumn.length > 0 && comparator.compare(finishColumn, curColPosition.lastName) > 0) ||
                    (startColumn.length > 0 && comparator.compare(startColumn, curColPosition.firstName) < 0))
                    return false;
            }
            else
            {
                if ((startColumn.length > 0 && comparator.compare(startColumn, curColPosition.lastName) > 0) ||
                    (finishColumn.length > 0 && comparator.compare(finishColumn, curColPosition.firstName) < 0))
                    return false;
            }

            boolean outOfBounds = false;

            file.reset(mark);
            long curOffset = file.skipBytes((int) curColPosition.offset); 
            assert curOffset == curColPosition.offset;
            while (file.bytesPastMark(mark) < curColPosition.offset + curColPosition.width && !outOfBounds)
            {
                IColumn column = emptyColumnFamily.getColumnSerializer().deserialize(file);
                if (reversed)
                    blockColumns.addFirst(column);
                else
                    blockColumns.addLast(column);

                /* see if we can stop seeking. */
                if (!reversed && finishColumn.length > 0)
                    outOfBounds = comparator.compare(column.name(), finishColumn) >= 0;
                else if (reversed && startColumn.length > 0)
                    outOfBounds = comparator.compare(column.name(), startColumn) >= 0;
                    
                if (outOfBounds)
                    break;
            }

            if (reversed)
                curRangeIndex--;
            else
                curRangeIndex++;
            return true;
        }

        public void close() throws IOException
        {
            if(closeFileWhenDone)
                file.close();
        }
    }
}