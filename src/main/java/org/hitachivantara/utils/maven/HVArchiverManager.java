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

import org.codehaus.plexus.archiver.Archiver;
import org.codehaus.plexus.archiver.UnArchiver;
import org.codehaus.plexus.archiver.manager.DefaultArchiverManager;
import org.codehaus.plexus.archiver.manager.NoSuchArchiverException;

public class HVArchiverManager extends DefaultArchiverManager {

  @Override public Archiver getArchiver( String archiverName ) throws NoSuchArchiverException {
    if ( "zip".equals( archiverName ) ) {
      archiverName = "parallel-zip";
    }
    if ( "dir".equals( archiverName ) ) {
      archiverName = "parallel-dir";
    }
    return super.getArchiver( archiverName );
  }

  @Override public UnArchiver getUnArchiver( String unArchiverName ) throws NoSuchArchiverException {
    if ( "zip".equals( unArchiverName ) ) {
      unArchiverName = "parallel-zip";
    }
    return super.getUnArchiver( unArchiverName );
  }
}
