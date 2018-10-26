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
    return super.getArchiver( archiverName );
  }

  @Override public UnArchiver getUnArchiver( String unArchiverName ) throws NoSuchArchiverException {
    if ( "zip".equals( unArchiverName ) ) {
      unArchiverName = "parallel-zip";
    }
    return super.getUnArchiver( unArchiverName );
  }
}
