package mirror;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;

import mirror.UpdateTree.Node;

public class UpdateTreeTest {

  private final UpdateTree root = UpdateTree.newRoot();

  @Test
  public void addFileInRoot() {
    root.add(Update.newBuilder().setPath("foo.txt").build());
    assertThat(root.getChildren().size(), is(1));
    assertThat(root.getChildren().get(0).getName(), is("foo.txt"));
  }

  @Test
  public void addDirectoryInRoot() {
    root.add(Update.newBuilder().setPath("foo").setDirectory(true).build());
    assertThat(root.getChildren().size(), is(1));
    assertThat(root.getChildren().get(0).getName(), is("foo"));
    assertThat(root.getChildren().get(0).isDirectory(), is(true));
  }

  @Test
  public void addFileInSubDirectory() {
    root.add(Update.newBuilder().setPath("bar").setDirectory(true).build());
    root.add(Update.newBuilder().setPath("bar/foo.txt").build());
    assertThat(root.getChildren().size(), is(1));
    Node bar = root.getChildren().get(0);
    assertThat(bar.getChildren().size(), is(1));
    assertThat(bar.getChildren().get(0).getName(), is("foo.txt"));
  }

  @Test
  public void addFileInMissingSubDirectory() {
    // e.g. if bar/ was gitignored, but then bar/foo.txt is explicitly included,
    // we'll create a placeholder bar/ entry in the local/remote UpdateTree
    root.add(Update.newBuilder().setPath("bar/foo.txt").build());
    assertThat(root.getChildren().size(), is(1));
    Node bar = root.getChildren().get(0);
    assertThat(bar.getName(), is("bar"));
    assertThat(bar.getPath(), is("bar"));
    assertThat(bar.getModTime(), is(0L));
    assertThat(bar.getChildren().size(), is(1));
    assertThat(bar.getChildren().get(0).getName(), is("foo.txt"));
  }

  @Test
  public void addDirectoryInSubDirectory() {
    root.add(Update.newBuilder().setPath("bar").setDirectory(true).build());
    root.add(Update.newBuilder().setPath("bar/foo").setDirectory(true).build());
    assertThat(root.getChildren().size(), is(1));
    Node bar = root.getChildren().get(0);
    assertThat(bar.getChildren().size(), is(1));
    assertThat(bar.getChildren().get(0).getName(), is("foo"));
    assertThat(bar.getChildren().get(0).isDirectory(), is(true));
  }

  @Test
  public void changeFileToADirecotry() {
    root.add(Update.newBuilder().setPath("bar").build());
    root.add(Update.newBuilder().setPath("bar").setDirectory(true).build());
    assertThat(root.getChildren().get(0).isDirectory(), is(true));
  }

  @Test
  public void changeDirectoryToAFile() {
    root.add(Update.newBuilder().setPath("bar").setDirectory(true).build());
    root.add(Update.newBuilder().setPath("bar/sub").setDirectory(true).build());
    root.add(Update.newBuilder().setPath("bar").build());
    assertThat(root.getChildren().get(0).isDirectory(), is(false));
    assertThat(root.getChildren().get(0).getChildren().size(), is(0));
  }

  @Test
  public void addingTheRootDoesNotDuplicateIt() {
    assertThat(root.root.getModTime(), is(0L));
    root.add(Update.newBuilder().setPath("").setModTime(1L).build());
    assertThat(root.getChildren().size(), is(0));
    assertThat(root.root.getModTime(), is(1L));
  }

  @Test
  public void deleteFileMarksTheNodeAsDeleted() {
    root.add(Update.newBuilder().setPath("foo.txt").setModTime(1L).build());
    root.add(Update.newBuilder().setPath("foo.txt").setDelete(true).build());
    assertThat(root.getChildren().size(), is(1));
    assertThat(root.getChildren().get(0).getUpdate().getDelete(), is(true));
    assertThat(root.getChildren().get(0).getModTime(), is(2L));
  }

  @Test
  public void deleteSymlinkMarksTheNodeAsDeleted() {
    root.add(Update.newBuilder().setPath("foo.txt").setSymlink("bar").build());
    root.add(Update.newBuilder().setPath("foo.txt").setDelete(true).build());
    assertThat(root.getChildren().size(), is(1));
    assertThat(root.getChildren().get(0).getUpdate().getDelete(), is(true));
    assertThat(root.getChildren().get(0).getUpdate().getSymlink(), is(""));
  }

  @Test
  public void deleteDirectoryMarksTheNodeAsDeletedAndRemovesAnyChildren() {
    root.add(Update.newBuilder().setPath("foo").setDirectory(true).build());
    root.add(Update.newBuilder().setPath("foo/bar.txt").build());
    root.add(Update.newBuilder().setPath("foo").setDelete(true).build());
    assertThat(root.getChildren().size(), is(1));
    assertThat(root.getChildren().get(0).getUpdate().getDelete(), is(true));
    assertThat(root.getChildren().get(0).getChildren().size(), is(0));
  }

  @Test
  public void deleteThenCreateFile() {
    root.add(Update.newBuilder().setPath("foo.txt").setModTime(1L).build());
    root.add(Update.newBuilder().setPath("foo.txt").setModTime(2L).setDelete(true).build());
    assertThat(root.getChildren().size(), is(1));
    assertThat(root.getChildren().get(0).getUpdate().getDelete(), is(true));
    // now it's re-created
    root.add(Update.newBuilder().setPath("foo.txt").setModTime(3L).build());
    assertThat(root.getChildren().get(0).getUpdate().getDelete(), is(false));
    assertThat(root.getChildren().get(0).getModTime(), is(3L));
  }
  
  @Test
  public void deleteFileTwiceDoesNotRetickModTime() {
    root.add(Update.newBuilder().setPath("foo.txt").setModTime(1L).build());
    root.add(Update.newBuilder().setPath("foo.txt").setDelete(true).build());
    assertThat(root.getChildren().size(), is(1));
    assertThat(root.getChildren().get(0).getModTime(), is(2L));
    root.add(Update.newBuilder().setPath("foo.txt").setDelete(true).build());
    assertThat(root.getChildren().get(0).getModTime(), is(2L));
  }


  @Test(expected = IllegalArgumentException.class)
  public void failsIfPathStartsWithSlash() {
    root.add(Update.newBuilder().setPath("/foo").build());
  }

  @Test(expected = IllegalArgumentException.class)
  public void failsIfPathEndsWithSlash() {
    root.add(Update.newBuilder().setPath("foo/").build());
  }
}
