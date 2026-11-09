// Physical exact-record comparison. No result from this tool is a camera/FPS PASS.
#define main existingRecordWindowBenchmarkMain
#include "lq001_aio_record_window_benchmark.cpp"
#undef main

namespace {
void benchmarkOwned(const Bytes& packet, unsigned frames, bool stream) {
    constexpr std::int64_t prefixBytes=13654, reservation=2LL*1024*1024*1024;
    require(prefixBytes+frames*(packet.size()+28u)+16384u<reservation,"bounded reservation exceeded");
    OwnedFile file;
    require(::posix_fallocate64(file.fd,0,reservation)==0,"physical reservation failed");
    Bytes prefix(static_cast<std::size_t>(prefixBytes),0x79);
    require(::pwrite64(file.fd,prefix.data(),prefix.size(),0)==static_cast<ssize_t>(prefix.size()) &&
        ::fsync(file.fd)==0,"prefix durability failed");
    Existing writer(file.fd,file.path.data(),prefixBytes,reservation);
    require(writer.admission()==Existing::Admission::Active && writer.kernelAioEnabled(),"AIO not admitted");
    const std::size_t capacity=packet.size()+32768u+28u;
    void* allocation=nullptr;
    require(::posix_memalign(&allocation,16384u,capacity)==0,"bounded source allocation failed");
    std::unique_ptr<std::uint8_t,decltype(&std::free)> owned(static_cast<std::uint8_t*>(allocation),&std::free);
    std::memset(owned.get(),0,capacity);
    std::array<std::uint8_t,28> header{};
    std::int64_t offset=prefixBytes;
    double total=0,maximum=0,syncMs=0;
    unsigned above40=0;
    for (unsigned frame=0;frame<frames;++frame) {
        header.fill(static_cast<std::uint8_t>(frame));
        const auto head=static_cast<std::size_t>(offset%writer.alignment())+header.size();
        auto* payload=owned.get()+head;
        // This is storage-only isolation; packet preparation is not measured or claimed here.
        std::memcpy(payload,packet.data(),packet.size());
        const auto started=Clock::now();
        require(writer.writeOwnedRecord(header.data(),header.size(),payload,packet.size(),offset,
            reservation,owned.get(),capacity,stream),"complete owned record failed");
        const auto elapsed=std::chrono::duration<double,std::milli>(Clock::now()-started).count();
        total+=elapsed; maximum=std::max(maximum,elapsed); if (elapsed>40) ++above40;
        offset+=static_cast<std::int64_t>(header.size()+packet.size());
        if (frame==0u || (frame+1u)%120u==0u) {
            const auto syncStarted=Clock::now();
            require(::fdatasync(file.fd)==0,"periodic durability failed");
            syncMs+=std::chrono::duration<double,std::milli>(Clock::now()-syncStarted).count();
        }
    }
    require(writer.ownedRecordWrites()==frames && writer.kernelAioSubmissions()==writer.kernelAioCompletions(),
        "owned route or completion counts mismatch");
    require(writer.close() && ::ftruncate64(file.fd,offset)==0 && ::fsync(file.fd)==0,"final durability failed");
    Bytes review(prefix.size()); readExact(file.fd,review.data(),review.size(),0);
    require(review==prefix,"prefix altered");
    review.resize(packet.size()+header.size());
    auto readOffset=prefixBytes;
    for (unsigned frame=0;frame<frames;++frame) {
        readExact(file.fd,review.data(),review.size(),readOffset);
        header.fill(static_cast<std::uint8_t>(frame));
        require(std::memcmp(review.data(),header.data(),header.size())==0 &&
            std::memcmp(review.data()+header.size(),packet.data(),packet.size())==0,"record byte mismatch");
        readOffset+=static_cast<std::int64_t>(review.size());
    }
    require(readOffset==offset,"full readback length mismatch");
    std::printf("OWNED_STREAM_RESULT backend=%s frames=%u packet_bytes=%zu mean_ms=%.6f max_ms=%.6f "
        "writes_above_40ms=%u periodic_sync_ms=%.6f bit_exact=true full_file_readback=true "
        "max_pending_requests=4 max_pending_bytes=8388608 scope=storage_not_camera_fps\n",
        stream?"immutable_refill":"owned_group",frames,packet.size(),total/frames,maximum,above40,syncMs);
    std::fflush(stdout);
}
} // namespace

int main(int argc,char** argv) {
    try {
        require(argc==4,"Expected actual.p010 group|stream frame-count");
        const std::string backend=argv[2],count=argv[3];
        require(backend=="group" || backend=="stream","unknown backend");
        require(!count.empty() && count.find_first_not_of("0123456789")==std::string::npos,"invalid count");
        const auto frames=std::stoul(count);
        require(frames>=2u && frames<=128u,"frame count outside bounded diagnostic range");
        benchmarkOwned(recordedPacket(argv[1]),static_cast<unsigned>(frames),backend=="stream");
        return 0;
    } catch (const std::exception& failure) {
        std::fprintf(stderr,"OWNED_STREAM_FAILED reason=%s errno=%d\n",failure.what(),errno);
        return 1;
    }
}
