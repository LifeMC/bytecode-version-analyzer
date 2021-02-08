# What is Contributing?
Contributing means helping this project to grow. It makes you a Contributor in this repository. This the page for reading before contributing.

# Difference between Contributor and Collaborator
Contributor is a person outside of the core development team and contributed to this repository via making pull requests.
Collaborator is a person inside of the core development team and have write access to this repository.

# Contributing to this repository
Step by step:
* First, fork this project.
  * Second, create a branch for your changes. (e.g: "fix-npe")
    This can be done easily using "git switch -c fix-npe". It will create a new branch based on current branch and switch to it.
    If you are not on main branch, you can switch to it using "git checkout main".

  * Then, make changes to the created branch via commits.
    This can be done either using some IDE, Git GUI or via commandline using "git commit -m <commit message>".

  * Finally, open a pull request for your branch to be merged.
    You can do this from GitHub page of this project. Just be sure you've pushed the changes to your fork and refresh the page.
    There should be a open a pull request button if you did all the steps correctly.

You must fill the pull request template before publishing a pull request.
We will review your pull request and merge into main or another target branch if we've found your changes great.

Don't be fear, we are all kind & formal here. Just open an issue if you don't know how to fix it. If you know how to fix it,
Then just follow the above guidelines and open a pull request. If your pull request is merged, you are now a Contributor. Congratulations!

**Also read the <a href="https://github.com/LifeMC/LifeSkript/blob/master/PROJECT_PREFERENCES.md">project preferences</a> before making any contribution!**

# Cloning and building the JAR
Step by step:
* Clone this repository via your IDE or command-line. (git clone https://github.com/<organization or user>/<project>.git)
* Run mvnw.cmd verify if you are on Windows, or do ./mvnw verify from terminal if you are on Linux or macOS.

# Properly testing the changes you made
* You should enable Java Assertions. (via "java -ea -jar X.jar", the **-ea** does this.)
