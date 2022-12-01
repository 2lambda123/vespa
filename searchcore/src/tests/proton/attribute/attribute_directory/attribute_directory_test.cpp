// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/searchcore/proton/attribute/attribute_directory.h>
#include <vespa/searchcore/proton/attribute/attributedisklayout.h>
#include <vespa/searchlib/test/directory_handler.h>
#include <vespa/vespalib/gtest/gtest.h>
#include <vespa/vespalib/stllike/asciistream.h>
#include <vespa/vespalib/stllike/string.h>
#include <filesystem>

#include <vespa/log/log.h>
LOG_SETUP("attribute_directory_test");

using search::IndexMetaInfo;
using search::SerialNum;
using search::test::DirectoryHandler;

namespace proton {

namespace {

vespalib::string toString(IndexMetaInfo &info) {
    vespalib::asciistream os;
    bool first = true;
    for (auto &snap : info.snapshots()) {
        if (!first) {
            os << ",";
        }
        first = false;
        if (snap.valid) {
            os << "v";
        } else {
            os << "i";
        }
        os << snap.syncToken;
    }
    return os.str();
}

bool hasAttributeDir(const std::shared_ptr<AttributeDirectory> &dir) {
    return static_cast<bool>(dir);
}

bool hasWriter(const std::unique_ptr<AttributeDirectory::Writer> &writer) {
    return static_cast<bool>(writer);
}

}

class Fixture : public DirectoryHandler {
public:

    std::shared_ptr<AttributeDiskLayout> _diskLayout;

    Fixture()
        : DirectoryHandler("attributes"),
          _diskLayout(AttributeDiskLayout::create("attributes"))
    {
    }

    ~Fixture() { }

    vespalib::string getDir() { return _diskLayout->getBaseDir(); }

    vespalib::string getAttrDir(const vespalib::string &name) { return getDir() + "/" + name; }

    void assertDiskDir(const vespalib::string &name) {
        EXPECT_TRUE(std::filesystem::is_directory(std::filesystem::path(name)));
    }

    void assertAttributeDiskDir(const vespalib::string &name) {
        assertDiskDir(getAttrDir(name));
    }

    void assertNotDiskDir(const vespalib::string &name) {
        EXPECT_FALSE(std::filesystem::exists(std::filesystem::path(name)));
    }

    void assertNotAttributeDiskDir(const vespalib::string &name) {
        assertNotDiskDir(getAttrDir(name));
    }

    vespalib::string getSnapshotDirComponent(SerialNum serialNum) {
        vespalib::asciistream os;
        os << "snapshot-";
        os << serialNum;
        return os.str();
    }

    vespalib::string getSnapshotDir(const vespalib::string &name, SerialNum serialNum) {
        return getAttrDir(name) + "/" + getSnapshotDirComponent(serialNum);
    }

    void assertSnapshotDir(const vespalib::string &name, SerialNum serialNum) {
        assertDiskDir(getSnapshotDir(name, serialNum));
    }

    void assertNotSnapshotDir(const vespalib::string &name, SerialNum serialNum) {
        assertNotDiskDir(getSnapshotDir(name, serialNum));
    }

    void assertSnapshots(const vespalib::string &name, const vespalib::string &exp) {
        vespalib::string attrDir(getAttrDir(name));
        IndexMetaInfo info(attrDir);
        info.load();
        vespalib::string act = toString(info);
        EXPECT_EQ(exp, act);
    }

    auto createAttributeDir(const vespalib::string &name) { return _diskLayout->createAttributeDir(name); }
    auto getAttributeDir(const vespalib::string &name) { return _diskLayout->getAttributeDir(name); }
    void removeAttributeDir(const vespalib::string &name, SerialNum serialNum) { return _diskLayout->removeAttributeDir(name, serialNum); }
    auto createFooAttrDir() { return createAttributeDir("foo"); }
    auto getFooAttrDir() { return getAttributeDir("foo"); }
    void removeFooAttrDir(SerialNum serialNum) { removeAttributeDir("foo", serialNum); }
    void assertNotGetAttributeDir(const vespalib::string &name) {
        auto dir = getAttributeDir(name);
        EXPECT_FALSE(static_cast<bool>(dir));
        assertNotAttributeDiskDir(name);
    }
    void assertGetAttributeDir(const vespalib::string &name, std::shared_ptr<AttributeDirectory> expDir) {
        auto dir = getAttributeDir(name);
        EXPECT_TRUE(static_cast<bool>(dir));
        EXPECT_EQ(expDir, dir);
    }
    void assertCreateAttributeDir(const vespalib::string &name, std::shared_ptr<AttributeDirectory> expDir) {
        auto dir = getAttributeDir(name);
        EXPECT_TRUE(static_cast<bool>(dir));
        EXPECT_EQ(expDir, dir);
    }

    void setupFooSnapshots(SerialNum serialNum) {
        auto dir = createFooAttrDir();
        EXPECT_TRUE(hasAttributeDir(dir));
        auto writer = dir->getWriter();
        writer->createInvalidSnapshot(serialNum);
        std::filesystem::create_directory(std::filesystem::path(writer->getSnapshotDir(serialNum)));
        writer->markValidSnapshot(serialNum);
        assertAttributeDiskDir("foo");
    }

    void invalidateFooSnapshots(bool removeDir) {
        auto dir = createFooAttrDir();
        auto writer = dir->getWriter();
        writer->invalidateOldSnapshots(10);
        writer->removeInvalidSnapshots();
        if (removeDir) {
            writer->removeDiskDir();
        }
        assertGetAttributeDir("foo", dir);
    }

    void makeInvalidSnapshot(SerialNum serialNum) {
        auto dir = createFooAttrDir();
        EXPECT_TRUE(hasAttributeDir(dir));
        dir->getWriter()->createInvalidSnapshot(serialNum);
    }

    void makeValidSnapshot(SerialNum serialNum) {
        auto dir = createFooAttrDir();
        auto writer = dir->getWriter();
        writer->createInvalidSnapshot(serialNum);
        std::filesystem::create_directory(std::filesystem::path(writer->getSnapshotDir(serialNum)));
        writer->markValidSnapshot(serialNum);
    }

};

class AttributeDirectoryTest : public Fixture, public testing::Test {};

TEST_F(AttributeDirectoryTest, can_create_attribute_directory)
{
    auto dir = createFooAttrDir();
    EXPECT_TRUE(hasAttributeDir(dir));
}

TEST_F(AttributeDirectoryTest, attribute_directory_is_persistent)
{
    assertNotGetAttributeDir("foo");
    auto dir = createFooAttrDir();
    EXPECT_TRUE(hasAttributeDir(dir));
    assertGetAttributeDir("foo", dir);
}

TEST_F(AttributeDirectoryTest, can_remove_attribute_directory)
{
    auto dir = createFooAttrDir();
    EXPECT_TRUE(hasAttributeDir(dir));
    assertGetAttributeDir("foo", dir);
    removeFooAttrDir(10);
    assertNotGetAttributeDir("foo");
}

TEST_F(AttributeDirectoryTest, can_create_attribute_directory_with_one_snapshot)
{
    assertNotGetAttributeDir("foo");
    auto dir = createFooAttrDir();
    EXPECT_TRUE(hasAttributeDir(dir));
    assertNotAttributeDiskDir("foo");
    dir->getWriter()->createInvalidSnapshot(1);
    assertAttributeDiskDir("foo");
    assertSnapshots("foo", "i1");
}

TEST_F(AttributeDirectoryTest, can_prune_attribute_snapshots)
{
    auto dir = createFooAttrDir();
    assertNotAttributeDiskDir("foo");
    auto writer = dir->getWriter();
    writer->createInvalidSnapshot(2);
    std::filesystem::create_directory(std::filesystem::path(writer->getSnapshotDir(2)));
    writer->markValidSnapshot(2);
    writer->createInvalidSnapshot(4);
    std::filesystem::create_directory(std::filesystem::path(writer->getSnapshotDir(4)));
    writer->markValidSnapshot(4);
    writer.reset();
    assertAttributeDiskDir("foo");
    assertSnapshots("foo", "v2,v4");
    dir->getWriter()->invalidateOldSnapshots();
    assertSnapshots("foo", "i2,v4");
    dir->getWriter()->removeInvalidSnapshots();
    assertSnapshots("foo", "v4");
}

TEST_F(AttributeDirectoryTest, attribute_directory_is_not_removed_if_valid_snapshots_remain)
{
    setupFooSnapshots(20);
    auto dir = getFooAttrDir();
    EXPECT_TRUE(hasAttributeDir(dir));
    dir->getWriter()->createInvalidSnapshot(30);
    assertSnapshots("foo", "v20,i30");
    removeFooAttrDir(10);
    assertGetAttributeDir("foo", dir);
    assertAttributeDiskDir("foo");
    assertSnapshots("foo", "v20");
}

TEST_F(AttributeDirectoryTest, attribute_directory_is_removed_if_no_valid_snapshots_remain)
{
    setupFooSnapshots(5);
    auto dir = getFooAttrDir();
    EXPECT_TRUE(hasAttributeDir(dir));
    dir->getWriter()->createInvalidSnapshot(30);
    assertSnapshots("foo", "v5,i30");
    removeFooAttrDir(10);
    assertNotGetAttributeDir("foo");
}

TEST_F(AttributeDirectoryTest, attribute_directory_is_not_removed_due_to_pruning_and_disk_dir_is_kept)
{
    setupFooSnapshots(5);
    invalidateFooSnapshots(false);
    assertAttributeDiskDir("foo");
}

TEST_F(AttributeDirectoryTest, attribute_directory_is_not_removed_due_to_pruning_but_disk_dir_is_removed)
{
    setupFooSnapshots(5);
    invalidateFooSnapshots(true);
    assertNotAttributeDiskDir("foo");
}

TEST(BasicDirectoryTest, initial_state_tracks_disk_layout)
{
    std::filesystem::create_directory(std::filesystem::path("attributes"));
    std::filesystem::create_directory(std::filesystem::path("attributes/foo"));
    std::filesystem::create_directory(std::filesystem::path("attributes/bar"));
    IndexMetaInfo fooInfo("attributes/foo");
    IndexMetaInfo barInfo("attributes/bar");
    fooInfo.addSnapshot({true, 4, "snapshot-4"});
    fooInfo.addSnapshot({false, 8, "snapshot-8"});
    fooInfo.save();
    barInfo.addSnapshot({false, 5, "snapshot-5"});
    barInfo.save();
    Fixture f;
    f.assertAttributeDiskDir("foo");
    f.assertAttributeDiskDir("bar");
    auto foodir = f.getFooAttrDir();
    EXPECT_TRUE(hasAttributeDir(foodir));
    auto bardir = f.getAttributeDir("bar");
    EXPECT_TRUE(hasAttributeDir(bardir));
    f.assertNotGetAttributeDir("baz");
    f.assertSnapshots("foo", "v4,i8");
    f.assertSnapshots("bar", "i5");
    f.makeInvalidSnapshot(12);
    f.makeValidSnapshot(16);
    f.assertSnapshots("foo", "v4,i8,i12,v16");
}

TEST_F(AttributeDirectoryTest, snapshot_removal_removes_correct_snapshot_directory)
{
    setupFooSnapshots(5);
    std::filesystem::create_directory(std::filesystem::path(getSnapshotDir("foo", 5)));
    std::filesystem::create_directory(std::filesystem::path(getSnapshotDir("foo", 6)));
    assertSnapshotDir("foo", 5);
    assertSnapshotDir("foo", 6);
    invalidateFooSnapshots(false);
    assertNotSnapshotDir("foo", 5);
    assertSnapshotDir("foo", 6);
    invalidateFooSnapshots(true);
    assertNotSnapshotDir("foo", 5);
    assertNotSnapshotDir("foo", 6);
}

TEST_F(AttributeDirectoryTest, can_get_nonblocking_writer)
{
    auto dir = createFooAttrDir();
    auto writer = dir->getWriter();
    EXPECT_TRUE(hasWriter(writer));
    auto writer2 = dir->tryGetWriter();
    EXPECT_FALSE(hasWriter(writer2));
    writer.reset();
    writer2 = dir->tryGetWriter();
    EXPECT_TRUE(hasWriter(writer2));
    writer = dir->tryGetWriter();
    EXPECT_FALSE(hasWriter(writer));
}

}

GTEST_MAIN_RUN_ALL_TESTS()
