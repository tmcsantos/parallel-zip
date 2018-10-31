/*
 * Copyright (C) 2018 by Hitachi Vantara
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package org.hitachivantara.utils.maven;

import org.codehaus.plexus.archiver.ArchiveFinalizer;
import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.archiver.FinalizerEnabled;
import org.codehaus.plexus.archiver.UnArchiver;
import org.codehaus.plexus.components.io.fileselectors.FileInfo;
import org.codehaus.plexus.components.io.fileselectors.FileSelector;
import org.codehaus.plexus.logging.AbstractLogEnabled;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


public abstract class AbstractParallelZipUnArchiver
  extends AbstractLogEnabled
  implements UnArchiver, FinalizerEnabled {

  private File destDirectory;
  private File destFile;
  private File sourceFile;

  private List<ArchiveFinalizer> finalizers;
  private FileSelector[] fileSelectors;

  private Boolean overwrite = Boolean.TRUE;
  private Boolean useJvmChmod = Boolean.TRUE;
  private Boolean ignorePermissions = Boolean.FALSE;

  public AbstractParallelZipUnArchiver() {
    // no op
  }

  public AbstractParallelZipUnArchiver( final File sourceFile ) {
    this.sourceFile = sourceFile;
  }

  @Override public void addArchiveFinalizer( ArchiveFinalizer finalizer ) {
    if ( finalizers == null ) {
      finalizers = new ArrayList<>();
    }
    finalizers.add( finalizer );
  }

  @Override public void setArchiveFinalizers( List<ArchiveFinalizer> archiveFinalizers ) {
    finalizers = archiveFinalizers;
  }

  @Override public void extract() throws ArchiverException {
    validate();
    execute();
    runArchiveFinalizers();
  }

  private void runArchiveFinalizers() throws ArchiverException {
    if ( finalizers != null ) {
      for ( ArchiveFinalizer archiveFinalizer : finalizers ) {
        archiveFinalizer.finalizeArchiveExtraction( this );
      }
    }
  }


  @Override public void extract( String path, File outputDirectory ) throws ArchiverException {
    validate( path, outputDirectory );
    execute( path, outputDirectory );
    runArchiveFinalizers();
  }

  protected void execute() throws ArchiverException {
  }

  protected void execute( String path, File outputDirectory ) throws ArchiverException {
  }

  protected void validate( String path, File outputDirectory ) {
  }

  protected void validate() throws ArchiverException {
    if ( sourceFile == null ) {
      throw new ArchiverException( "The source file isn't defined." );
    }
    if ( sourceFile.isDirectory() ) {
      throw new ArchiverException( "The source must not be a directory." );
    }

    if ( !sourceFile.exists() ) {
      throw new ArchiverException( "The source file " + sourceFile + " doesn't exist." );
    }

    if ( destDirectory == null && destFile == null ) {
      throw new ArchiverException( "The destination isn't defined." );
    }

    if ( destDirectory != null && destFile != null ) {
      throw new ArchiverException( "You must choose between a destination directory and a destination file." );
    }

    if ( destDirectory != null && !destDirectory.isDirectory() ) {
      destFile = destDirectory;
      destDirectory = null;
    }

    if ( destFile != null && destFile.isDirectory() ) {
      destDirectory = destFile;
      destFile = null;
    }
  }

  @Override public File getDestDirectory() {
    return destDirectory;
  }

  @Override public void setDestDirectory( File destDirectory ) {
    this.destDirectory = destDirectory;
  }

  @Override public File getDestFile() {
    return destFile;
  }

  @Override public void setDestFile( File destFile ) {
    this.destFile = destFile;
  }

  @Override public File getSourceFile() {
    return sourceFile;
  }

  @Override public void setSourceFile( File sourceFile ) {
    this.sourceFile = sourceFile;
  }

  @Override public boolean isOverwrite() {
    return overwrite;
  }

  @Override public void setOverwrite( boolean b ) {
    overwrite = b;
  }

  @Override public void setFileSelectors( FileSelector[] selectors ) {
    fileSelectors = selectors;
  }

  @Override public FileSelector[] getFileSelectors() {
    return fileSelectors;
  }

  @Override public void setUseJvmChmod( boolean useJvmChmod ) {
    this.useJvmChmod = useJvmChmod;
  }

  @Override public boolean isUseJvmChmod() {
    return useJvmChmod;
  }

  @Override public boolean isIgnorePermissions() {
    return ignorePermissions;
  }

  @Override public void setIgnorePermissions( boolean ignorePermissions ) {
    this.ignorePermissions = ignorePermissions;
  }

  protected boolean isSelected( final FileInfo fileInfo ) throws ArchiverException {
    if ( fileSelectors != null ) {
      for ( FileSelector fileSelector : fileSelectors ) {
        try {
          if ( !fileSelector.isSelected( fileInfo ) ) {
            return false;
          }
        } catch ( IOException ioe ) {
          throw new ArchiverException(
            "Failed to check, whether " + fileInfo.getName() + " is selected: " + ioe.getMessage(), ioe );
        }
      }
    }
    return true;
  }
}
