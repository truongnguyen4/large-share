# Báo cáo các tính năng đã triển khai – LargeShare P2P

Phạm vi: refactor và nâng cấp ứng dụng theo `CLAUDE.md` và tài liệu *Invention Disclosure*
("Localized Peer-to-Peer OTA Distribution System for Enterprise Fleets of Android Rugged Devices").

Kiểm thử trên 3 thiết bị thật (Datalogic **Memor 12** – Android 15, **Xiaomi 23043RP34G** – Android 14,
**moto g37** – Android 16), thêm 1 node PC trên cùng mạng, và bộ **75 test JUnit** chạy trên JVM (gồm mô phỏng swarm 24 thiết bị).

---

## + Feature: Auto reconnect
- **Hiệu quả**
  - Mỗi peer lỗi chỉ bị tạm hoãn (backoff mũ 1s → 30s, có jitter ±25%), **không bị loại vĩnh viễn**; nên sau lỗi tạm thời luôn có lần thử lại.
  - Rớt kết nối giữa chừng: thử nối lại ngay với cùng peer (tối đa 3 lần, 100–400 ms) trước khi leo thang backoff. Peer đang truyền dữ liệu thì lỗi không bị phạt tăng dần.
  - Khi mạng quay lại, mọi backoff được xoá để thử ngay (không phải chờ hết hạn).
  - Tải tiếp từ đúng chỗ dừng: danh sách chunk đã xác minh được lưu xuống đĩa (`.state`), kể cả khi app bị kill.
- **Độ tin cậy**
  - NSD tự khởi động lại khi `discoverServices`/`registerService` lỗi (backoff, không lặp nóng).
  - Wi-Fi Direct client tự gia nhập lại group khi group mất.
  - Swarm Wi-Fi Direct: chủ group chết thì member tự rời group, vào group khác và **tải tiếp phần còn thiếu**, không tải lại từ đầu (xem mục "Wi-Fi Direct tự tổ chức").
- **Đã kiểm chứng trên máy thật**
  - Cắt Wi-Fi của Memor 14 giây khi đang tải file 600 MB: app tự tạm dừng, sau đó tự tải tiếp, SHA-256 cuối cùng khớp.
  - Kill app giữa chừng rồi mở lại: phục hồi 526/1200 chunk đã xác minh, chỉ tải phần còn lại.
  - Wi-Fi Direct: tắt rồi bật lại root, client tự vào lại group và hoàn tất.

## + Feature: Check identity
- **Hiệu quả**
  - Xác thực hai chiều **không tốn thêm round-trip**: mỗi request mang chữ ký HMAC-SHA256 (id, thời gian, nonce, method, đường dẫn) và mỗi response cũng được ký lại. Chỉ tốn một phép HMAC trên chuỗi ngắn.
  - Manifest do root ký, nên peer lạ không thể phát bảng hash chunk giả.
  - Id trong NSD được đối chiếu với id peer tự chứng minh; báo cáo tiến độ bị gắn với id đã xác thực (không giả mạo được thiết bị khác).
- **Độ tin cậy / an toàn**
  - Chống phát lại: giới hạn lệch giờ ±5 phút, và nonce của các request thay đổi trạng thái (POST) chỉ dùng được một lần.
  - Thiết bị sai chữ ký bị ban theo IP (5 lần sai trong 60 giây thì ban 60 giây); peer thất bại xác thực bị bỏ qua 5 phút.
  - Tên file trong manifest từ mạng được làm sạch, **chống path traversal** (trước đây peer từ xa quyết định đường dẫn ghi file).
- **Đã kiểm chứng trên máy thật**: seeker dùng sai fleet key bị root từ chối (`authRejects=1`), nhận `UNAUTHORIZED`, không tải được byte nào.

## + Feature: Handle network failures gracefully
- **Hiệu quả**
  - `NetworkMonitor` theo dõi mạng mặc định. Khi mất mạng thì tạm dừng ngay (không tốn pin và băng thông vào các lần thử vô ích), có debounce để bỏ qua nhiễu ngắn.
  - Khi mạng về (kể cả đổi IP do **roaming giữa các AP**), NSD được đăng ký và dò tìm lại để quảng bá đúng địa chỉ mới.
  - Lỗi được phân loại (mạng / bận / sai danh tính / không có dữ liệu / giao thức / chính mình) nên mỗi loại có cách phản ứng riêng, không xử lý chung một kiểu.
  - Báo cáo tiến độ về root được gộp, giới hạn tần suất (có jitter) và **bản tin 100% cuối cùng được thử lại cho đến khi gửi được**. Trước đây nó có thể bị cắt mất khi tắt luồng.
  - Mạng chặn multicast/mDNS thì dùng **static peers** (nhập địa chỉ tay) hoặc **peer exchange**.
  - Android chỉ cho app scan Wi-Fi 4 lần mỗi 2 phút. Swarm nhớ các group đã thấy trong 90 giây và join lại bằng tên + kênh đã biết, nên **không phải chờ lượt scan kế tiếp**.
- **Độ tin cậy**
  - Ổ đĩa đầy được phát hiện sớm (kiểm tra dung lượng trước khi cấp phát file).
  - Mọi timeout và giới hạn kích thước đều có hạn mức (độ dài dòng, số header, kích thước body, thời gian đọc request).

## + Feature: High concurrency support
- **Hiệu quả**
  - **Admission control**: server chỉ phục vụ tối đa N kết nối cùng lúc (mặc định 24); client thừa nhận ngay HTTP 503 rồi sang peer khác, không xếp hàng.
  - Đọc chunk bằng `pread` theo vị trí, không khoá: nhiều luồng phục vụ cùng lúc mà không giẫm lên nhau. Chunk được stream bằng bộ đệm 64 KB thay vì cấp phát 512 KB cho mỗi kết nối.
  - Chọn chunk ngẫu nhiên có xét nguyên tử (một khoá duy nhất), không chunk nào bị hai worker cùng lấy; chọn peer theo trọng số (ít lỗi, nhanh thì ưu tiên) để phân tán tải.
  - Bộ nhớ có giới hạn: tối đa 256 peer trong cache, 512 địa chỉ trong bảng peer exchange.
  - Tải lan ngang: mỗi seeker phục vụ ngay khi có manifest; **peer exchange** giúp các thiết bị tự tìm nhau mà không phụ thuộc multicast (không phình theo số thiết bị).
  - Swarm Wi-Fi Direct: **slot lease** giới hạn N member mỗi group (Android không tự giới hạn được), và mỗi máy tốt nghiệp lại thành thêm một nguồn phát, nên số nguồn tăng theo từng thế hệ.
- **Số liệu (mô phỏng JVM, socket thật, root giới hạn 4 kết nối)**

  | Kịch bản | Kết quả |
  |---|---|
  | 24 thiết bị cùng tải 6 MB | Cả 24 đều đúng 100%; root chỉ phát **21,6%** lượng dữ liệu của mô hình client–server |
  | 12 thiết bị chỉ biết địa chỉ root (không NSD, chỉ peer exchange) | Root chỉ phát **13,2%** |
  | 8 client cùng lúc vào server giới hạn 2 | 2 được phục vụ, 6 bị từ chối nhanh bằng 503 |

- **Số liệu trên máy thật**: PC + 2 điện thoại cùng tải file 300 MB (hai máy tự tìm nhau qua NSD): PC chỉ phát 846/1200 chunk, **~30% do hai điện thoại chia sẻ chéo cho nhau**.

## + Feature: Data consistency assurance
- **Hiệu quả**
  - **Xác minh hai lớp** đúng như tài liệu: Lớp 1 – SHA-256 từng chunk 512 KB (chunk hỏng chỉ tải lại đúng chunk đó); Lớp 2 – SHA-256 cả file trước khi cho phép dùng. Trước đây Lớp 2 chưa có (đoạn code bị comment) và hash cả file bị tính sai (hash của hash).
  - Cả hai lớp được tính trong **một lượt đọc duy nhất** khi tạo manifest.
  - Khớp tài liệu: kích thước chunk là **512 KB = 524.288 byte** (code cũ dùng 536.576 byte).
- **Độ tin cậy**
  - Ghi vào `file.part`, chỉ **đổi tên nguyên tử** sang tên thật sau khi Lớp 2 đạt; file dở dang hoặc hỏng không bao giờ xuất hiện như file hoàn chỉnh.
  - Lớp 2 sai thì tự quét tìm đúng chunk hỏng, đánh dấu thiếu, tải lại rồi xác minh lại (tối đa 3 vòng). Phần quét và phát hiện đúng chunk hỏng có test; cả vòng sửa này chưa được chạy end-to-end vì khi thử, Lớp 1 đã chặn chunk hỏng trước khi tới Lớp 2.
  - Khi resume, mọi chunk đã lưu đều được băm lại; chunk hỏng trên đĩa bị loại thay vì tin vào file trạng thái.
  - Manifest từ mạng được kiểm tra chặt (kích thước chunk, số chunk khớp kích thước, định dạng hash) nên không thể ép app cấp phát bộ nhớ hay file vô lý.
  - Server chỉ chào chunk đã xác minh và đang có trên đĩa (trước đây nó trả cả vùng toàn số 0 kèm HTTP 200). `SharedData` toàn cục không an toàn luồng được thay bằng `SwarmSession` tách biệt cho từng phiên.
- **Đã kiểm chứng trên máy thật**: sau khi cố ý làm hỏng 2 chunk trong file nguồn, app từ chối chunk hỏng (SHA-256 lỗi), ban peer sau 3 lần phát dữ liệu sai và **không công bố file nào**; sửa nguồn rồi chạy lại thì tải nốt và hash đúng.

## + Feature: Fault tolerance
- **Hiệu quả**
  - Không có điểm lỗi đơn: sau khi một thiết bị nào đó có đủ file, root chết cũng không sao – các thiết bị còn lại tự hoàn tất từ nhau (có test).
  - Peer-drop recovery: chunk đang tải được trả lại hàng chờ, peer vào backoff, supervisor chuyển sang peer khác. Không còn trường hợp mất hết peer rồi tải đứng như bản cũ.
  - Peer chập chờn, trả dữ liệu hỏng hoặc rớt giữa chunk đều không làm hỏng download (có test).
  - Giữ chỗ peer rảnh: peer chưa có chunk mới (đang tải dở) được hỏi lại định kỳ thay vì bị bỏ.
  - **Endgame**: khi mọi chunk còn thiếu đều đang được tải, một phiên rảnh được tải trùng chunk đó (tối đa 2 bản), bản nào về trước thì dùng. Nhờ vậy vài chunk cuối không bị một peer vừa biến mất giữ lại (trên máy thật: 34 s → 15 s).
- **Độ tin cậy**
  - Bộ giám sát (supervisor) luôn giữ tối đa 5 phiên peer chạy song song, tự cân bằng và không bao giờ kết thúc "vì hết peer".
  - Tự làm mới discovery khi 15 giây không có peer dùng được.
  - Luồng nhận kết nối của server không còn có thể chết vì `RejectedExecutionException` (lỗi cũ).
  - Server tự đóng kết nối bị kẹt khi ghi; kết nối nhàn rỗi trả luồng sau 5 giây.

---

## Hiệu năng (số liệu đo)

**Tối ưu sau khi đo (v2):** cho phép tối đa 3 phiên song song với cùng một peer (tổng vẫn ≤ 5 phiên). Bản v1 chỉ có 1 chunk "đang bay" mỗi peer, nên đường truyền phải chờ thêm một RTT (~24 ms) sau mỗi chunk 512 KB.

| PC → Memor 12, file 300 MB, cùng AP Wi-Fi 6 | Lần 1 | Lần 2 | Lần 3 | Trung bình |
|---|---|---|---|---|
| TCP thô (nc, không giao thức, không ghi đĩa) | 19,9–30,1 | 18,5–28,0 | – | ~23 MB/s |
| App v1 (1 phiên/peer) | 10,5 | 6,3 | 8,5 | **8,4 MB/s** (~37% đường truyền) |
| App v2 (≤3 phiên/peer) | 32,0 | 16,1 | 25,3 | **24,5 MB/s** (~100% đường truyền, nhanh **2,9×**) |

Tất cả các lần đều cho SHA-256 khớp. Trong lúc tải, app dùng CPU trung bình 52–76% của một nhân (Memor có 8 nhân, tức khoảng 7–10% toàn máy) và RAM (PSS) 68–77 MB.

| Kịch bản thật (đo bằng v1) | Kết quả |
|---|---|
| pipa → Memor qua AP (NSD), 300 MB | 71 s, 4,4 MB/s (dao động 0,5–10,6) |
| pipa → Memor qua **Wi-Fi Direct**, 300 MB | ~20 s, ~15 MB/s, 326 MB đi qua `p2p0` |
| PC + 2 điện thoại cùng tải 300 MB | PC chỉ phát 846/1200 chunk; **29,5%** do hai máy chia sẻ chéo |
| Mất Wi-Fi 14 s | phát hiện mất mạng sau 1,5 s (debounce); tải lại **1,3 s** sau khi mạng về |
| Root Wi-Fi Direct khởi động lại | client vào lại group sau 15 s |
| Kill app ở ~43% | phục hồi 526/1200 chunk, tổng cộng chỉ truyền thêm 6 chunk dư |

| Chi phí xử lý trên Memor 12 (QCS4490) | |
|---|---|
| Xác minh Lớp 2 (cả file) | 300 MB trong 0,78–1,10 s; 600 MB trong 1,25 s |
| Ghi bộ nhớ / SHA-256 của hệ thống | 297 MB/s / 833 MB/s, không phải nút thắt |

**Mô phỏng có giới hạn băng thông** (JVM, mỗi thiết bị upload ≤ 4 MB/s, file 16 MB):

| N thiết bị | Client/server (đo) | P2P swarm v2 | Root phát (P2P) |
|---|---|---|---|
| 4 | 16,2 s | 6,0 s | 22 MB |
| 8 | 32,2 s | 7,3 s | 24 MB |
| 16 | 64,2 s | 8,3 s | 26 MB |
| 32 | 128 s (lý thuyết) | **8,6 s** (nhanh 15×) | 29 MB (thay vì 512 MB, tiết kiệm 94%) |

Chi phí phụ (JVM): kiểm tra danh tính khoảng 5 µs/request; cấp một chunk ngẫu nhiên 11 µs (file 2,5 GB) đến 159 µs (file 100 GB); manifest 740 MB/s trên PC.

**Phát hiện vận hành:** khi Memor tắt màn hình và vào **Doze**, Android chặn mạng của app (kết nối timeout), trong khi shell vẫn kết nối được. Điều này xác nhận cần Foreground Service (xem mục Giới hạn số 3).

## + Feature: Chạy nền (Foreground Service) và tách UI / Service
- **Kiến trúc**
  - `ShareService` (foreground service, loại `connectedDevice`) sở hữu engine (`ShareManager`), partial wake lock, Wi-Fi lock và notification (tiến độ + nút Stop).
  - UI chỉ dùng `ShareApi`: `prepareFile`, `start(SessionConfig)`, `stop`, `getSnapshot`, `addListener`. Dữ liệu về UI là `SessionSnapshot` bất biến (pha, tiến độ, tốc độ, file, danh sách peer), được gom tối đa khoảng 7 lần/giây. `MainActivity` không còn import `manager`, `storage`, `security`, `network` hay `model`.
  - Đóng hoặc xoay UI không còn dừng phiên; mở lại thì UI vẽ lại đúng trạng thái từ snapshot.
- **Đã kiểm chứng trên máy thật**
  - Memor tải 300 MB trong khi app ở nền, màn hình tắt và **Doze sâu bị ép (`IDLE`)**: tải xong, SHA-256 khớp. Trước khi có service, cùng điều kiện này làm mọi kết nối bị timeout.
  - Mở lại UI trên Xiaomi khi root vẫn chạy: hiển thị đúng "Stop sharing", "SHARING" và tiến độ peer nhận được trong lúc UI đóng.
  - Nút Stop trên notification (Memor) và nút Stop trong UI (Xiaomi) đều dừng phiên và gỡ foreground; phiên tự kết thúc (`LEFT`) thì service tự rời foreground.

## + Feature: Wi-Fi Direct tự tổ chức (mô hình "tốt nghiệp") – không cần hạ tầng, không cấu hình cây
Đây là cơ chế Wi-Fi Direct duy nhất của app. Bản thử nghiệm "cây cấu hình tĩnh" (uplink/downlink cho từng máy, relay qua SoftAP) đã bị gỡ: phải cấu hình tay từng máy, chỉ nối được 2 group và chặng SoftAP chậm hơn khoảng 10 lần.
- **Cách làm**
  - Ban đầu mọi máy muốn nhận dữ liệu đều là *member tương lai*. Root tạo group thế hệ 0 và nhận tối đa **N member**.
  - Member nào đã có đủ file **và đã xác minh SHA-256** sẽ rời group (trả slot ngay), rồi tự lập group mới ở **thế hệ +1** và làm owner để phát tiếp. Slot trống trong group cũ được nhường cho máy kế tiếp.
  - Quảng bá group bằng chính SSID mà owner phát ra: `DIRECT-LS-<fleet tag>-<thế hệ>-<id group>`. Member tìm group bằng scan Wi-Fi thường (quét mọi kênh). Passphrase suy ra từ fleet key và id group và không bao giờ phát lên sóng, nên **thấy SSID cũng không vào được**.
  - Chọn group theo thứ tự: cùng kênh với AP đang dùng (nếu có) → **thế hệ trẻ nhất** (kéo tải ra xa root) → sóng mạnh nhất (theo nấc 5 dB) → ngẫu nhiên khi ngang nhau (để đám đông tự rải đều).
  - **Slot lease**: member gia hạn slot qua `GET /group` (có xác thực HMAC) mỗi 4 giây; lease hết hạn sau 15 giây. Owner đã đủ N thì từ chối, member rời đi và thử lại theo **backoff tăng dần có jitter** (8 → 16 → 32 → tối đa 60 s).
  - Mỗi máy chỉ ở **một** group tại một thời điểm (đúng giới hạn 1 radio) và không đụng tới kết nối Wi-Fi hạ tầng.
- **Chịu lỗi**
  - **Owner chết nhưng group vẫn còn** (app bị kill, framework vẫn giữ group): 20 giây không trả lời thì member rời group, đưa group đó vào danh sách tránh trong 2 phút rồi tìm group khác. Bản đầu tiên không có cơ chế này và member bị kẹt ~3 phút.
  - Group "mồ côi" của chính máy từ lần chạy trước bị gỡ khi khởi động lại. Thêm lệnh `--ez stop true` để dừng sạch từ adb/MDM: lệnh này gỡ luôn group, khác với force-stop.
  - Join thất bại hoặc timeout 15 s: group bị tạm tránh và máy tự thử group khác. Hết group để thử thì join root bằng tên đã biết, có gợi ý kênh (cần cho kênh DFS).
- **Đã kiểm chứng trên 3 máy thật** (file 100 MB, mỗi lượt SHA-256 đều khớp):

  | Kịch bản | Kết quả |
  |---|---|
  | Chuỗi 3 thế hệ, N = 1: Xiaomi (root, gen 0) → Memor → moto | Memor tốt nghiệp lên **gen 1**; moto vào group của Memor trong 0,4 s, tải 100 MB trong ~4,5 s và tốt nghiệp lên **gen 2** |
  | Kill owner gen 1 khi moto đang tải (~22 MB) | moto phát hiện sau 20 s, vào root sau 1,6 s và **chỉ tải phần còn thiếu**; thành công ở cả 2 lần chạy |
  | 2 máy cùng lúc vào root N = 1 (tranh slot) | 1 máy được slot, máy kia bị từ chối rồi vào sau. Tổng thời gian: **61 s → 44 s** (backoff) **→ 25 s** (nhớ group, không chờ scan) |
  | 2 máy cùng lúc vào root N = 2 | **34 s → 15 s** nhờ endgame |
  | Tốc độ mỗi chặng p2p | 11–25 MB/s (100 MB trong 4–9 s) |
- **Giới hạn**
  - **Chưa báo tiến độ về root** (theo yêu cầu, phần này để thiết kế sau). Hiện root chỉ thấy các member trực tiếp của nó.
  - Môi trường test có router ở kênh DFS 5540 MHz, nên một máy đang nối AP khó join group ở kênh khác (5180) do giới hạn chạy song song 2 kênh. Đổi lại, thuật toán ưu tiên group cùng kênh. Khi Memor làm root, Xiaomi không join được group của Memor trên 5540. Khi không có Wi-Fi hạ tầng (đúng mục tiêu của mô hình) thì ràng buộc này không còn.
  - Thời gian phát hiện owner chết là 20 giây (đánh đổi với việc không bỏ nhầm một owner chỉ đang bận).

## Các tính năng khác thuộc tài liệu sáng chế

- **Wi-Fi Direct (chế độ không hạ tầng)**: root tạo group tự động; SSID và passphrase **suy ra từ fleet key**, nên seeker tự gia nhập **không cần ai bấm xác nhận trên máy**. Dữ liệu đi qua giao diện `p2p0`, không qua router.
  - Đo trên máy thật: 300 MB trong ~20 giây (**14,8 MB/s**, nhanh khoảng 3,5 lần so với qua router nhà), SHA-256 khớp, và tự nối lại khi root khởi động lại.
- **File người dùng chọn**: trước đây app luôn dùng `/sdcard/file-shared.zip` và bỏ qua file đã chọn. Giờ file chọn được chia sẻ tại chỗ (không sao chép file nhiều GB). Hash manifest chạy ngoài UI thread và được tái sử dụng, không băm hai lần.
- **Giao diện**: dòng trạng thái theo pha (PREPARING / SEARCHING / DOWNLOADING / PAUSED_NETWORK / VERIFYING / COMPLETED …) kèm tốc độ và số peer; ô nhập fleet key; ô nhập static peers. Tracker tiến độ trên root nhận đủ 100% (đã xác nhận trên máy thật).
  - Thẻ **"This device"**: IP của máy theo từng mạng (Wi-Fi, Wi-Fi Direct, Ethernet, Hotspot), port 8888 và id máy; bôi đen để copy được. Mobile data và VPN bị ẩn vì peer không dùng được. Service đọc IP mỗi 3 giây và ngay khi trạng thái đổi, nên địa chỉ `192.168.49.x` hiện ra khoảng 1 giây sau khi vào hoặc lập group (đã kiểm chứng trên Memor và moto).
  - Thẻ file của **host**: tóm tắt gồm tên, thư mục, kích thước, số chunk (kèm kích thước chunk cuối) và SHA-256 cả file. **Không còn in hash từng chunk**: UI cũng không nhận danh sách đó nữa (với file 100 GB là 200.000 chuỗi).
- **Khả năng test tự động**: có thể khởi chạy phiên bằng intent, ví dụ `am start … --es role seeker --es peers 192.168.1.52:8888 --ez autostart true`.

## Giới hạn đã biết và việc nên làm tiếp

1. **Wi-Fi Direct nhiều tầng**: dùng swarm tự tổ chức theo thế hệ, đã chạy tới gen 2 trên 3 máy (mục "Wi-Fi Direct tự tổ chức"). Còn lại: báo tiến độ về root khi không có hạ tầng, và thử với nhiều hơn 3 máy.
2. **Xác thực bằng fleet key dùng chung (HMAC)**: đảm bảo xác thực và toàn vẹn, **không mã hoá** đường truyền (HTTP rõ). Lộ key (ví dụ mất một thiết bị) ảnh hưởng cả fleet đến khi đổi key. Khóa mặc định chỉ để dev, cần cấp key qua MDM. Bản production nên dùng chứng chỉ/khóa riêng cho từng thiết bị (Android Keystore) và TLS.
3. ~~Chưa có Foreground Service~~ **Đã làm** (xem mục "Chạy nền" bên dưới). Còn lại: chưa xin miễn tối ưu pin (battery optimization), chưa tự khởi động lại phiên sau khi máy reboot.
4. **Quy mô "hàng nghìn thiết bị"**: được chứng minh bằng thiết kế (có giới hạn tài nguyên ở mọi tầng) và mô phỏng 24 node, **chưa** thử trên hàng nghìn máy thật.
5. **Tốc độ trên mạng gia đình dao động** 0,5–10 MB/s giữa 2 điện thoại qua router; đây là đặc tính của Wi-Fi nhà, không phải của thuật toán (cùng file qua Wi-Fi Direct ổn định ~15 MB/s).
6. **Tương thích**: manifest mới (512 KB, hash cả file đúng, có chữ ký) **không tương thích** với bản cũ của app.
7. Tên dịch vụ NSD tracker là `_largeprogress._tcp` vì tên trong tài liệu `_largeshareprogress` dài quá 15 ký tự mà chuẩn DNS-SD cho phép.

## Danh sách thay đổi chính

| Thành phần | Thay đổi |
|---|---|
| `SeekManager` | Viết lại: supervisor + phiên theo peer, reconnect, resume, Layer-2, sửa chunk hỏng |
| `SeedManager`, `HttpServer` | Admission control, parse có giới hạn, xác thực, ban IP, peer exchange, chỉ chào chunk có thật |
| `HttpClient` | Lỗi có phân loại, xác minh response, kiểm tra độ dài, peer exchange |
| `StorageManager` | I/O định vị thread-safe, `.part` + đổi tên nguyên tử, resume có xác minh, kiểm tra dung lượng |
| `NsdDiscoveryManager` | Hàng đợi resolve tuần tự, chống trùng, tự restart, TXT tag cho swarm |
| `WifiDirectDiscoveryManager` | Bỏ: thay bằng `WifiDirectSwarm` |
| Mới | `Identity`, `IdentityStore`, `PeerRegistry`, `NetworkMonitor`, `ProgressReporter`, `SwarmSession`, `StorageLocations`, `PeerException` |
| Bỏ | `SharedData` (singleton toàn cục), `PerformanceTracker` (mã chết) |
| `PeerRegistry` (v2) | Tối đa 3 phiên song song mỗi peer, ưu tiên rải sang peer khác, lỗi đồng thời chỉ tính một lần |
| Service | `ShareService` (foreground), `ShareApi`, `SessionSnapshot`, `SessionConfig`; `MainActivity` chỉ gọi API |
| Wi-Fi Direct | Mới: `WifiDirectSwarm` (mô hình tốt nghiệp), `GroupAdvert` (SSID quảng bá + chọn group + passphrase), `SlotLeases`; endpoint `GET /group` |
| UI / mạng | Mới: `LocalAddresses` (IP theo từng interface), `SessionSnapshot.addresses` / `servePort`; `FileInfo` chỉ còn tóm tắt (không chứa hash từng chunk) |
| `BitSetMetadata` | Endgame: tải trùng có giới hạn các chunk đang bị giữ |
| Test | 75 test JUnit (`app/src/test`), gồm `SwarmSimulationTest` với socket thật, `GroupAdvertTest`, `LocalAddressesTest`; `PerformanceBenchmarkTest` (chạy khi đặt `LS_BENCH=1`) |

Chạy test: `./gradlew testDebugUnitTest`. Chạy benchmark: `LS_BENCH=1 ./gradlew cleanTestDebugUnitTest testDebugUnitTest --tests '*PerformanceBenchmarkTest'`.
