package com.typesafe.sbt.git

import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.api.{Git => PGit}
import java.io.File
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Ref

import scala.util.Try


// TODO - This class needs a bit more work, but at least it lets us use porcelain and wrap some higher-level
// stuff on top of JGit, as needed for our plugin.
final class JGit(val repo: Repository) extends GitReadonlyInterface {
  val porcelain = new PGit(repo)


  def create(): Unit = repo.create()

  def branch: String = repo.getBranch

  def branches: Seq[Ref] = {
    import collection.JavaConverters._
    porcelain.branchList.call.asScala
  }

  def tags: Seq[Ref] = {
    import collection.JavaConverters._
    porcelain.tagList.call().asScala
  }

  def checkoutBranch(branch: String): Unit = {
    // First, if remote branch exists, we auto-track it.
    val exists = branches exists (_.getName == ("refs/heads/" + branch))
    if(exists)  porcelain.checkout.setName(branch).call()
    else {
      // TODO - find upstream...
      import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode
      val upstream = "origin/" + branch
      porcelain.checkout.setCreateBranch(true).setName(branch)
                .setUpstreamMode(SetupUpstreamMode.SET_UPSTREAM)
                .setStartPoint(upstream).call()
    }
  }

  def headCommit: Option[ObjectId] =
    Option(repo.resolve("HEAD"))

  def headCommitSha: Option[String] =
    headCommit map (_.name)

  def currentTags: Seq[String] = {
    import collection.JavaConverters._
    for {
      hash <- headCommit.map(_.name).toSeq
      unpeeledTag <- tags
      taghash = tagHash(unpeeledTag)
      if taghash == hash
      ref = unpeeledTag.getName
      if ref startsWith "refs/tags/"
    } yield ref drop 10
  }


  def tagHash(tag: Ref) = {
    // Annotated (signed) and plain tags work differently,
    // plain ones have the null PeeledObjectId
    val peeled = repo.peel(tag)
    val id =
      if (peeled.getPeeledObjectId ne null)
        peeled.getPeeledObjectId
      else
        peeled.getObjectId
    id.getName
  }

  override def describedVersion: Option[String] = {
    Try { porcelain.describe().call() } toOption
  }
}

object JGit {

  /** Creates a new git instance from a base directory. */
  def apply(base: File) =
    try (new JGit({
      new FileRepositoryBuilder().findGitDir(base).build
    })) catch {
      // This is thrown if we never find the git base directory.  In that instance, we'll assume root is the base dir.
      case e: IllegalArgumentException =>
        val defaultGitDir = new File(base, ".git")
        new JGit({ new FileRepositoryBuilder().setGitDir(defaultGitDir).build()})
    }

  /** Clones from a given URI into a local directory of your choosing. */
  def clone(from: String, to: File, remoteName: String = "origin", cloneAllBranches: Boolean = true, bare: Boolean = false): JGit = {
    val git = PGit.cloneRepository.setURI(from).setRemote(remoteName).setBare(bare).setCloneAllBranches(cloneAllBranches).setDirectory(to).call()
    new JGit(git.getRepository)
  }
}
